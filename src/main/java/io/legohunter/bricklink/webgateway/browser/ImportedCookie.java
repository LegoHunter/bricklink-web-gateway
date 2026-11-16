package io.legohunter.bricklink.webgateway.browser;

/**
 * One cookie from an imported session. It carries a live credential in {@code value}, so it is kept
 * in memory only and its text form never shows the value.
 *
 * @param expiresEpochSeconds expiry as epoch seconds, or a value {@code <= 0} for a session-only cookie
 * @param sameSite            {@code Strict}, {@code Lax}, {@code None} or {@code null}
 */
public record ImportedCookie(String name, String value, String domain, String path, double expiresEpochSeconds,
                             boolean httpOnly, boolean secure, String sameSite) {

    @Override
    public String toString() {
        return "ImportedCookie[name=" + name + ", domain=" + domain + ", value=<hidden>]";
    }
}
