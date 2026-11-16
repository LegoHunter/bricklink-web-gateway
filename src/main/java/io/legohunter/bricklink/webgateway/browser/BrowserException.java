package io.legohunter.bricklink.webgateway.browser;

/** The browser could not be started, or a request through it did not complete. */
public class BrowserException extends RuntimeException {

    public BrowserException(String message, Throwable cause) {
        super(message, cause);
    }

    public BrowserException(String message) {
        super(message);
    }
}
