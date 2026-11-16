package io.legohunter.bricklink.webgateway.session;

import java.time.Instant;
import java.util.List;

/**
 * The session as last observed. Holds no cookie values and no full URLs.
 *
 * @param lastProbeAt         when the last probe finished; {@code null} before the first one
 * @param lastSignedInAt      when a probe last ended signed in; {@code null} if never since startup
 * @param lastProbeHttpStatus status of the last probe's final response; {@code 0} if the request did not complete
 * @param lastProbeLocation   host and path the last probe ended on
 * @param layers              expiry of the cookie behind each session layer
 */
public record SessionSnapshot(SessionState state, Instant lastProbeAt, Instant lastSignedInAt,
                              int lastProbeHttpStatus, String lastProbeLocation, List<Layer> layers,
                              boolean browserOpen) {

    /** @param expiresAt {@code null} if the cookie is absent or session-only */
    public record Layer(SessionLayer layer, Instant expiresAt) {
    }
}
