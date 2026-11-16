package io.legohunter.bricklink.webgateway.session;

/**
 * The long-lived layers of the session, each identified by the cookie that carries it. Their expiry
 * dates show whether regular use is renewing the session and how long remains before a manual sign-in.
 */
public enum SessionLayer {

    /** BrickLink's token; about 28 days ahead and re-issued with use. */
    BRICKLINK_TOKEN("bricklink.lego-account.refresh-token"),

    /** BrickLink's portal session; about 28 days ahead and re-issued with use. */
    BRICKLINK_PORTAL_SESSION("bricklink.portal.session-id"),

    /** The LEGO Account sign-in; about 3 months ahead, not seen to renew. */
    LEGO_ACCOUNT("id.a");

    private final String cookieName;

    SessionLayer(String cookieName) {
        this.cookieName = cookieName;
    }

    public String cookieName() {
        return cookieName;
    }
}
