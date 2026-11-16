package io.legohunter.bricklink.webgateway.browser;

import java.util.List;

/**
 * The one browser that holds the BrickLink session.
 *
 * <p>Not thread-safe: every method must be called through {@link OperationQueue}, which runs all
 * browser work on a single thread (a Playwright requirement) and spaces the operations out.
 */
public interface BrowserSession {

    /**
     * Requests a URL with the browser's cookie jar, following redirects.
     *
     * @throws BrowserException if the browser could not be started or the request did not complete
     */
    FetchResult fetch(String url);

    /** Metadata of every cookie in the jar. Never the values. */
    List<CookieInfo> cookies();

    /** Replaces the whole cookie jar and writes it to the profile directory. */
    void replaceCookies(List<ImportedCookie> cookies);

    /** Closes the browser if it is running. The session stays in the profile directory. */
    void close();

    boolean isOpen();

    /** @param finalUrl the full URL the redirect chain ended on; pass it through SafeUrl before showing it */
    record FetchResult(int httpStatus, String finalUrl) {
    }

    /** @param expiresEpochSeconds expiry as epoch seconds, or a value {@code <= 0} for a session-only cookie */
    record CookieInfo(String name, String domain, double expiresEpochSeconds) {
    }
}
