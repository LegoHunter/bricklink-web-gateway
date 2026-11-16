package io.legohunter.bricklink.webgateway.logging;

import java.net.URI;

/**
 * Reduces a URL to host and path. Query strings and fragments on the sign-in redirects carry OAuth
 * codes and tokens, so a full URL must never reach a log line, a metric or an API response.
 */
public final class SafeUrl {

    public static final String UNPARSEABLE = "<unparseable>";

    private SafeUrl() {
    }

    public static String of(String url) {
        if (url == null || url.isBlank()) {
            return UNPARSEABLE;
        }
        try {
            URI uri = URI.create(url);
            if (uri.getHost() == null) {
                return UNPARSEABLE;
            }
            return uri.getHost() + (uri.getPath() == null ? "" : uri.getPath());
        } catch (IllegalArgumentException e) {
            return UNPARSEABLE;
        }
    }
}
