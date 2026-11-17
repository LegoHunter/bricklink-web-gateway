package io.legohunter.bricklink.webgateway.lot;

import io.legohunter.bricklink.webgateway.session.SessionState;

/** What can stop a lot operation. Each maps to one error code of the REST interface. */
public final class LotExceptions {

    private LotExceptions() {
    }

    /** BrickLink does not show this lot for the account. */
    public static class LotNotFoundException extends RuntimeException {
        public LotNotFoundException(long lotId) {
            super("BrickLink shows no lot " + lotId + " for this account");
        }
    }

    /** The environment guard refused the lot. Nothing was changed. */
    public static class LotGuardRefusedException extends RuntimeException {
        public LotGuardRefusedException(String message) {
            super(message);
        }
    }

    /** The guard could not check the lot (the BrickLink API did not answer). Nothing was changed. */
    public static class GuardUnavailableException extends RuntimeException {
        public GuardUnavailableException(String message) {
            super(message);
        }
    }

    /** BrickLink answered with something the gateway does not recognise. */
    public static class UnexpectedPageException extends RuntimeException {
        public UnexpectedPageException(String message) {
            super(message);
        }
    }

    /** BrickLink accepted the request but the read-back does not show the change. */
    public static class WriteNotAppliedException extends RuntimeException {
        public WriteNotAppliedException(String message) {
            super(message);
        }
    }

    /** The lot cannot take this change (for example its item type has no Extended Description). */
    public static class LotNotSupportedException extends RuntimeException {
        public LotNotSupportedException(String message) {
            super(message);
        }
    }

    /** The gateway is not signed in, so no lot operation can run. */
    public static class SessionUnavailableException extends RuntimeException {

        private final SessionState state;

        public SessionUnavailableException(SessionState state) {
            super("The gateway's BrickLink session is " + state);
            this.state = state;
        }

        public SessionState state() {
            return state;
        }
    }
}
