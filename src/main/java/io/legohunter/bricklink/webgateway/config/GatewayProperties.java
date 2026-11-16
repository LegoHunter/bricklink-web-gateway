package io.legohunter.bricklink.webgateway.config;

import java.nio.file.Path;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the gateway, bound from {@code bricklink.web-gateway.*}.
 *
 * @param baseUrl            BrickLink website origin.
 * @param probePath          A page only a signed-in user can open; the probe and the keep-alive visit it.
 * @param profileDir         Browser profile directory. It holds live session cookies: treat it as a secret.
 * @param browserChannel     Empty for Playwright's bundled Chromium (the cluster); {@code chrome} to use an
 *                           installed Chrome (a developer PC).
 * @param headless           Run the browser without a window.
 * @param minRequestGap      Minimum pause between two BrickLink operations.
 * @param browserIdleTimeout Close the browser after this long without an operation, to free memory.
 * @param operationTimeout   How long a caller waits for a queued operation before giving up.
 * @param navigationTimeout  Timeout of one browser request or page load.
 * @param adminToken         Bearer token for replacing the session. Empty means that endpoint is shut.
 * @param callerToken        Bearer token for the services that use the gateway. Empty means only the
 *                           admin token passes.
 * @param keepAlive          Scheduled keep-alive visit.
 */
@ConfigurationProperties(prefix = "bricklink.web-gateway")
public record GatewayProperties(
        @DefaultValue("https://www.bricklink.com") String baseUrl,
        @DefaultValue("/my.asp") String probePath,
        @DefaultValue("./.bricklink-web-gateway/profile") Path profileDir,
        @DefaultValue("") String browserChannel,
        @DefaultValue("true") boolean headless,
        @DefaultValue("2s") Duration minRequestGap,
        @DefaultValue("5m") Duration browserIdleTimeout,
        @DefaultValue("2m") Duration operationTimeout,
        @DefaultValue("45s") Duration navigationTimeout,
        @DefaultValue("") String adminToken,
        @DefaultValue("") String callerToken,
        @DefaultValue KeepAlive keepAlive) {

    /**
     * @param enabled      Run the scheduled keep-alive.
     * @param interval     Pause between two keep-alive visits.
     * @param initialDelay Pause before the first visit after startup.
     */
    public record KeepAlive(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("60m") Duration interval,
            @DefaultValue("30s") Duration initialDelay) {
    }

    public String probeUrl() {
        return baseUrl + probePath;
    }

    /** Never print a token. */
    @Override
    public String toString() {
        return "GatewayProperties[baseUrl=" + baseUrl + ", probePath=" + probePath + ", profileDir=" + profileDir
                + ", browserChannel=" + browserChannel + ", headless=" + headless + ", adminToken="
                + shown(adminToken) + ", callerToken=" + shown(callerToken) + ", keepAlive=" + keepAlive + "]";
    }

    private static String shown(String token) {
        return token == null || token.isBlank() ? "<unset>" : "<set>";
    }
}
