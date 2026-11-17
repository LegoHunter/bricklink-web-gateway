package io.legohunter.bricklink.webgateway.browser;

import java.util.List;
import java.util.Map;

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

    /** Like {@link #fetch} but also returns the response body. */
    HttpResult get(String url);

    /** Posts a URL-encoded form with the browser's cookie jar and returns the response. */
    HttpResult postForm(String url, Map<String, String> form);

    /** Posts one file as multipart form data with the browser's cookie jar and returns the response. */
    HttpResult postFile(String url, String referer, String fieldName, FilePart file);

    /**
     * Opens a page, changes controls on it and presses its own submit button, as a person would, so
     * the page's script builds the request and every other field keeps its current value.
     *
     * @param pageUrl        the page to open
     * @param readySelector  an element that exists once the page has loaded its data
     * @param actions        the changes to make before submitting
     * @param submitSelector the page's submit control
     * @param responseUrlPart part of the URL of the request the submit triggers
     * @return the body of that request's response
     * @throws PageShapeException if the page does not have the expected controls
     * @throws BrowserException   if the browser failed or the submit's response did not arrive
     */
    String submitPage(String pageUrl, String readySelector, List<PageAction> actions, String submitSelector,
                      String responseUrlPart);

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

    /** @param finalUrl the full URL the redirect chain ended on; pass it through SafeUrl before showing it */
    record HttpResult(int httpStatus, String finalUrl, String body) {
    }

    record FilePart(String fileName, String mimeType, byte[] content) {
    }

    /** @param expiresEpochSeconds expiry as epoch seconds, or a value {@code <= 0} for a session-only cookie */
    record CookieInfo(String name, String domain, double expiresEpochSeconds) {
    }

    /** One change to a control on a page. */
    sealed interface PageAction {

        /** Ticks a checkbox. */
        record Check(String selector) implements PageAction {
        }

        /** Replaces the text of an input or textarea. */
        record Fill(String selector, String value) implements PageAction {
        }
    }

    /** The page loaded but lacks a control the operation needs. */
    class PageShapeException extends RuntimeException {
        public PageShapeException(String message) {
            super(message);
        }
    }
}
