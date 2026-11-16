package io.legohunter.bricklink.webgateway.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import io.legohunter.bricklink.webgateway.config.GatewayProperties;
import org.springframework.stereotype.Component;

/**
 * Checks the bearer token on every endpoint that acts.
 *
 * <p>The gateway is reachable through Traefik like the other LCMS services, but unlike them it
 * holds a signed-in BrickLink session, so being on the network is not enough to use it. There are
 * two tokens: the <em>caller</em> token for the services that use the gateway, and the
 * <em>admin</em> token for replacing the session. The admin token also passes a caller check.
 *
 * <p>The check is called from inside the controller method rather than from a path-matching filter,
 * so no URL spelling can route around it. A token that is not configured shuts its endpoints: no
 * caller can succeed.
 */
@Component
public class TokenAuthorizer {

    private static final String BEARER = "Bearer ";

    private final byte[] adminToken;
    private final byte[] callerToken;

    public TokenAuthorizer(GatewayProperties properties) {
        this.adminToken = bytes(properties.adminToken());
        this.callerToken = bytes(properties.callerToken());
    }

    /** For replacing the session. */
    public void requireAdmin(String authorizationHeader) {
        if (adminToken == null) {
            throw new TokenException(Role.ADMIN, false);
        }
        if (!matches(adminToken, presented(authorizationHeader))) {
            throw new TokenException(Role.ADMIN, true);
        }
    }

    /** For using the session: probing, and the lot operations. */
    public void requireCaller(String authorizationHeader) {
        if (callerToken == null && adminToken == null) {
            throw new TokenException(Role.CALLER, false);
        }
        byte[] presented = presented(authorizationHeader);
        // Both comparisons always run, so timing does not show which token came close.
        boolean caller = matches(callerToken, presented);
        boolean admin = matches(adminToken, presented);
        if (!caller && !admin) {
            throw new TokenException(Role.CALLER, callerToken != null);
        }
    }

    private static boolean matches(byte[] expected, byte[] presented) {
        return expected != null && presented != null && MessageDigest.isEqual(expected, presented);
    }

    private static byte[] presented(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith(BEARER)) {
            return null;
        }
        String token = authorizationHeader.substring(BEARER.length()).trim();
        return token.isEmpty() ? null : token.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] bytes(String token) {
        return token == null || token.isBlank() ? null : token.trim().getBytes(StandardCharsets.UTF_8);
    }

    public enum Role {
        ADMIN, CALLER
    }

    public static class TokenException extends RuntimeException {

        private final Role role;
        private final boolean configured;

        TokenException(Role role, boolean configured) {
            super(configured
                    ? "The " + role.name().toLowerCase() + " token is missing or wrong"
                    : "No " + role.name().toLowerCase() + " token is configured");
            this.role = role;
            this.configured = configured;
        }

        public Role role() {
            return role;
        }

        /** {@code false} when the gateway has no such token at all, so no caller can succeed. */
        public boolean configured() {
            return configured;
        }
    }
}
