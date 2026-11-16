package io.legohunter.bricklink.webgateway.session;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Decides what a probe means from where its redirect chain ended.
 *
 * <p>One response is not conclusive on BrickLink. A classic page without an ASP session redirects to
 * {@code sessionSync.asp}, which rebuilds the session from the long-lived token cookies and redirects
 * back; a page without a valid token redirects to the sign-in route and on to the LEGO Account site.
 * So the browser follows the redirects with its cookie jar, and the final URL decides.
 */
public final class ProbeClassifier {

    private static final String BRICKLINK_HOST = "www.bricklink.com";
    private static final String LEGO_IDENTITY_HOST = "identity.lego.com";
    private static final Pattern SIGN_IN_PATH = Pattern.compile("/auth/|sign-in|login", Pattern.CASE_INSENSITIVE);

    private ProbeClassifier() {
    }

    /**
     * @param httpStatus status of the final response in the redirect chain
     * @param finalUrl   URL of that final response; may be {@code null} or unparseable
     */
    public static SessionState classify(int httpStatus, String finalUrl) {
        URI uri = parse(finalUrl);
        if (uri == null || uri.getHost() == null) {
            return SessionState.UNKNOWN;
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        String path = uri.getPath() == null ? "" : uri.getPath();
        // A server error says nothing about the session, wherever the chain ended.
        if (httpStatus >= 500) {
            return SessionState.UNKNOWN;
        }
        if (host.equals(LEGO_IDENTITY_HOST)) {
            return SessionState.SIGNED_OUT;
        }
        if (host.equals(BRICKLINK_HOST)) {
            if (SIGN_IN_PATH.matcher(path).find()) {
                return SessionState.SIGNED_OUT;
            }
            // 202 is the firewall's challenge page, 403/429 a block: neither is a sign-out.
            return httpStatus == 200 ? SessionState.SIGNED_IN : SessionState.UNKNOWN;
        }
        return SessionState.UNKNOWN;
    }

    private static URI parse(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            return URI.create(url);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
