package io.legohunter.bricklink.webgateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Keeps the deployment files in step with the rules the gateway depends on: one pod only, the
 * platform's runtime hardening, no environment named in the base, every overlay pointing at its own
 * environment, and no token in committed configuration.
 */
class DeploymentManifestTest {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final Path KUBERNETES = Path.of("kubernetes");
    private static final String APP = "bricklink-web-gateway";

    @Test
    void exactlyOnePodAndNeverTwoAtOnce() throws IOException {
        JsonNode deployment = yaml("base/deployment.yaml");

        assertThat(deployment.at("/spec/replicas").asInt()).isEqualTo(1);
        // RollingUpdate would start the new pod while the old one still holds the browser profile.
        assertThat(deployment.at("/spec/strategy/type").asText()).isEqualTo("Recreate");
    }

    @Test
    void containerRunsHardened() throws IOException {
        JsonNode pod = yaml("base/deployment.yaml").at("/spec/template/spec");
        JsonNode container = pod.at("/containers/0");

        assertThat(pod.at("/securityContext/runAsNonRoot").asBoolean()).isTrue();
        assertThat(pod.at("/securityContext/runAsUser").asInt()).isEqualTo(10001);
        assertThat(pod.at("/securityContext/fsGroup").asInt()).isEqualTo(10001);
        assertThat(pod.at("/securityContext/seccompProfile/type").asText()).isEqualTo("RuntimeDefault");
        assertThat(container.at("/securityContext/allowPrivilegeEscalation").asBoolean()).isFalse();
        assertThat(container.at("/securityContext/readOnlyRootFilesystem").asBoolean()).isTrue();
        assertThat(container.at("/securityContext/capabilities/drop/0").asText()).isEqualTo("ALL");
        assertThat(container.at("/lifecycle/preStop/sleep/seconds").asInt()).isEqualTo(10);
    }

    @Test
    void profileLivesOnThePersistentVolumeAndCredentialsStayFirst() throws IOException {
        JsonNode pod = yaml("base/deployment.yaml").at("/spec/template/spec");

        // The overlays patch volumes/0, so the credentials volume must stay first.
        assertThat(pod.at("/volumes/0/name").asText()).isEqualTo("external-secret");
        assertThat(pod.at("/volumes/0/secret/secretName").asText()).isEqualTo("set-by-overlay");
        assertThat(pod.at("/volumes/2/persistentVolumeClaim/claimName").asText()).isEqualTo(APP + "-profile");
        assertThat(pod.at("/containers/0/volumeMounts/2/mountPath").asText()).isEqualTo("/var/lib/bricklink-web-gateway");
        assertThat(Files.readString(Path.of("src/main/resources/application-kubernetes.yml")))
                .contains("profile-dir: /var/lib/bricklink-web-gateway/profile");
        assertThat(yaml("cluster-setup/profile-volume.yaml").at("/metadata/name").asText()).isEqualTo(APP + "-profile");
        assertThat(yaml("cluster-setup/profile-volume.yaml").at("/spec/accessModes/0").asText()).isEqualTo("ReadWriteOnce");
        // Comfortably above the browser's 50 MB cache ceiling.
        assertThat(yaml("cluster-setup/profile-volume.yaml").at("/spec/resources/requests/storage").asText()).isEqualTo("128Mi");
    }

    @Test
    void baseNamesNoEnvironment() throws IOException {
        for (String file : List.of("deployment.yaml", "service.yaml", "ingress.yaml", "kustomization.yaml")) {
            String text = Files.readString(KUBERNETES.resolve("base").resolve(file));
            assertThat(text).as(file).doesNotContain("services-sandbox", "services-dev", "services-prod",
                    "sandbox.internal", "dev.internal", "prod.internal", "SPRING_PROFILES_ACTIVE");
        }
        assertThat(yaml("base/ingress.yaml").at("/spec/routes/0/match").asText()).contains("set-by-overlay.invalid");
        assertThat(yaml("base/ingress.yaml").at("/spec/tls/secretName").asText()).isEqualTo("set-by-overlay");
    }

    @Test
    void theDeployPipelineCreatesNoVolumeOrNetworkPolicy() throws IOException {
        // The deploy runner has no rights on them, and the volume must outlive every deploy.
        JsonNode resources = yaml("base/kustomization.yaml").at("/resources");

        assertThat(resources).extracting(JsonNode::asText)
                .containsExactlyInAnyOrder("deployment.yaml", "service.yaml", "ingress.yaml");
    }

    @Test
    void routedOverHttpsOnly() throws IOException {
        JsonNode ingress = yaml("base/ingress.yaml");

        assertThat(ingress.at("/spec/entryPoints")).extracting(JsonNode::asText).containsExactly("websecure");
        assertThat(ingress.at("/spec/routes")).hasSize(1);
        assertThat(ingress.at("/spec/tls/secretName").asText()).isNotBlank();
    }

    @Test
    void serviceIsClusterInternal() throws IOException {
        assertThat(yaml("base/service.yaml").at("/spec/type").asText()).isEqualTo("ClusterIP");
    }

    @ParameterizedTest
    @ValueSource(strings = {"sandbox", "dev", "prod"})
    void overlayTargetsItsOwnEnvironmentOnly(String env) throws IOException {
        String dir = "overlays/" + env + "/";
        JsonNode kustomization = yaml(dir + "kustomization.yaml");

        assertThat(kustomization.at("/namespace").asText()).isEqualTo("services-" + env);
        assertThat(kustomization.at("/labels/0/pairs/app.kubernetes.io~1instance").asText()).isEqualTo(env);
        assertThat(kustomization.at("/labels/0/includeTemplates").asBoolean()).isTrue();
        assertThat(kustomization.at("/resources")).extracting(JsonNode::asText).containsExactly("../../base");
        assertThat(kustomization.at("/replicas/0/count").asInt()).isEqualTo(1);

        assertThat(yaml(dir + "deployment-env-patch.yaml").at("/spec/template/spec/containers/0/env/0/value").asText())
                .isEqualTo(env + ",kubernetes");
        assertThat(yaml(dir + "deployment-" + env + "-patch.yaml").at("/0/value").asText())
                .isEqualTo("services-" + env + "-external-secret");

        JsonNode ingressPatch = yaml(dir + "ingress-patch.yaml");
        assertThat(ingressPatch.at("/0/value").asText()).isEqualTo(
                "Host(`" + APP + "." + env + ".internal.legohunter.io`)");
        assertThat(ingressPatch.at("/1/value").asText()).isEqualTo(env + "-internal-legohunter-io-tls");

        for (String other : List.of("sandbox", "dev", "prod")) {
            if (other.equals(env)) {
                continue;
            }
            try (var files = Files.list(KUBERNETES.resolve(dir))) {
                for (Path file : files.toList()) {
                    assertThat(Files.readString(file)).as(file.toString())
                            .doesNotContain("services-" + other, other + ".internal", other + ",kubernetes");
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"sandbox", "dev", "prod"})
    void springProfileReadsOnlyItsOwnCredentialFile(String env) throws IOException {
        JsonNode profile = YAML.readTree(Files.readString(Path.of("src/main/resources/application-" + env + ".yml")));

        assertThat(profile.at("/lego/environment/code").asText()).isEqualTo(env);
        assertThat(profile.at("/lego/environment/production").asBoolean(false)).isEqualTo(env.equals("prod"));
        assertThat(profile.at("/spring/config/import/0").asText())
                .isEqualTo("optional:file:${import-path}/bricklink-web-gateway.yml");
        assertThat(profile.at("/spring/config/import/1").asText())
                .isEqualTo("optional:file:${import-path}/bricklink-client-api-keys.yml");

        // The stockroom guard: A is reserved for PROD, B for DEV, C for SANDBOX.
        JsonNode guard = profile.at("/bricklink/web-gateway/guard");
        assertThat(guard.path("production").asBoolean(false)).isEqualTo(env.equals("prod"));
        assertThat(guard.path("stockroom-id").asText("")).isEqualTo(switch (env) {
            case "sandbox" -> "C";
            case "dev" -> "B";
            default -> "";
        });
    }

    @Test
    void noTokenInAnyCommittedConfiguration() throws IOException {
        try (var files = Files.list(Path.of("src/main/resources"))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".yml")).toList()) {
                for (String name : List.of("admin-token", "caller-token")) {
                    JsonNode token = YAML.readTree(Files.readString(file)).at("/bricklink/web-gateway/" + name);
                    if (!token.isMissingNode()) {
                        // Empty, or a reference to an environment variable with an empty default.
                        assertThat(token.asText()).as(file + " " + name).matches("|\\$\\{[A-Z_]+:}");
                    }
                }
            }
        }
    }

    @Test
    void containerImageMatchesThePlaywrightDriverAndRunsUnprivileged() throws IOException {
        String containerfile = Files.readString(KUBERNETES.resolve("Containerfile"));
        Matcher version = Pattern.compile("<playwright.version>([^<]+)</playwright.version>")
                .matcher(Files.readString(Path.of("pom.xml")));
        assertThat(version.find()).isTrue();

        // The image carries the Chromium build this driver version expects.
        assertThat(containerfile).contains("FROM mcr.microsoft.com/playwright/java:v" + version.group(1) + "-noble");
        assertThat(containerfile).contains("USER 10001:10001", "ENV HOME=/tmp", "ENV PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1");
        // The arm64 image is built on amd64 runners without emulation, so it cannot run anything.
        assertThat(containerfile.lines().filter(line -> line.startsWith("RUN"))).isEmpty();
    }

    private static JsonNode yaml(String file) throws IOException {
        return YAML.readTree(Files.readString(KUBERNETES.resolve(file)));
    }
}
