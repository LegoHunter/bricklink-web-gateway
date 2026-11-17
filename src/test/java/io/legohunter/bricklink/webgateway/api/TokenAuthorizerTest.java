package io.legohunter.bricklink.webgateway.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;

import io.legohunter.bricklink.webgateway.api.TokenAuthorizer.Role;
import io.legohunter.bricklink.webgateway.api.TokenAuthorizer.TokenException;
import io.legohunter.bricklink.webgateway.config.GatewayProperties;
import org.junit.jupiter.api.Test;

class TokenAuthorizerTest {

    @Test
    void withoutConfiguredTokensNoCallerGetsIn() {
        for (String unset : new String[] {"", "   ", null}) {
            TokenAuthorizer tokens = new TokenAuthorizer(properties(unset, unset));

            for (String header : new String[] {null, "Bearer ", "Bearer anything", "Bearer null"}) {
                assertThatThrownBy(() -> tokens.requireAdmin(header))
                        .isInstanceOfSatisfying(TokenException.class, e -> {
                            assertThat(e.configured()).isFalse();
                            assertThat(e.role()).isEqualTo(Role.ADMIN);
                        });
                assertThatThrownBy(() -> tokens.requireCaller(header))
                        .isInstanceOfSatisfying(TokenException.class, e -> assertThat(e.configured()).isFalse());
            }
        }
    }

    @Test
    void eachTokenOpensOnlyItsOwnDoorExceptThatAdminMayAlsoCall() {
        TokenAuthorizer tokens = new TokenAuthorizer(properties("admin-secret", "caller-secret"));

        assertThatCode(() -> tokens.requireAdmin("Bearer admin-secret")).doesNotThrowAnyException();
        assertThatCode(() -> tokens.requireCaller("Bearer caller-secret")).doesNotThrowAnyException();
        assertThatCode(() -> tokens.requireCaller("Bearer admin-secret")).doesNotThrowAnyException();

        assertThatThrownBy(() -> tokens.requireAdmin("Bearer caller-secret"))
                .isInstanceOfSatisfying(TokenException.class, e -> assertThat(e.configured()).isTrue());
        assertThatThrownBy(() -> tokens.requireCaller("Bearer other")).isInstanceOf(TokenException.class);
        assertThatThrownBy(() -> tokens.requireCaller("caller-secret")).isInstanceOf(TokenException.class);
        assertThatThrownBy(() -> tokens.requireCaller(null)).isInstanceOf(TokenException.class);
    }

    @Test
    void anAdminTokenAloneStillLetsTheOwnerProbe() {
        TokenAuthorizer tokens = new TokenAuthorizer(properties("admin-secret", ""));

        assertThatCode(() -> tokens.requireCaller("Bearer admin-secret")).doesNotThrowAnyException();
        assertThatThrownBy(() -> tokens.requireCaller("Bearer ")).isInstanceOf(TokenException.class);
    }

    @Test
    void failureTextNeverShowsAToken() {
        TokenAuthorizer tokens = new TokenAuthorizer(properties("admin-secret", "caller-secret"));

        assertThatThrownBy(() -> tokens.requireAdmin("Bearer presented-secret"))
                .hasMessageNotContaining("admin-secret")
                .hasMessageNotContaining("presented-secret");
        assertThat(properties("admin-secret", "caller-secret").toString())
                .doesNotContain("admin-secret", "caller-secret");
    }

    private static GatewayProperties properties(String adminToken, String callerToken) {
        return new GatewayProperties("https://www.bricklink.com", "/my.asp", Path.of("unused"), "", true,
                Duration.ZERO, Duration.ofMinutes(5), Duration.ofSeconds(10), Duration.ofSeconds(10), adminToken,
                callerToken, new GatewayProperties.KeepAlive(false, Duration.ofHours(1), Duration.ZERO),
                new GatewayProperties.Guard(false, "C"));
    }
}
