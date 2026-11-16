package io.legohunter.bricklink.webgateway.session;

/** What the gateway knows about its BrickLink website session. */
public enum SessionState {

    /** The last probe ended on a signed-in page. BrickLink operations are allowed. */
    SIGNED_IN,

    /** The last probe ended on the sign-in route. A manual sign-in (session import) is needed. */
    SIGNED_OUT,

    /** A session import is in progress. Temporary. */
    RECOVERING,

    /**
     * BrickLink was unreachable or answered with something unexpected, or no probe has run yet.
     * Deliberately not treated as signed out: an outage must never prompt a sign-in.
     */
    UNKNOWN
}
