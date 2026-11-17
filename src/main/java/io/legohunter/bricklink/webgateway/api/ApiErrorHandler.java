package io.legohunter.bricklink.webgateway.api;

import io.legohunter.bricklink.webgateway.api.TokenAuthorizer.TokenException;
import io.legohunter.bricklink.webgateway.browser.BrowserException;
import io.legohunter.bricklink.webgateway.api.LotController.InvalidRequestException;
import io.legohunter.bricklink.webgateway.browser.OperationQueue.GatewayBusyException;
import io.legohunter.bricklink.webgateway.lot.LotExceptions.GuardUnavailableException;
import io.legohunter.bricklink.webgateway.lot.LotExceptions.LotGuardRefusedException;
import io.legohunter.bricklink.webgateway.lot.LotExceptions.LotNotFoundException;
import io.legohunter.bricklink.webgateway.lot.LotExceptions.LotNotSupportedException;
import io.legohunter.bricklink.webgateway.lot.LotExceptions.SessionUnavailableException;
import io.legohunter.bricklink.webgateway.lot.LotExceptions.UnexpectedPageException;
import io.legohunter.bricklink.webgateway.lot.LotExceptions.WriteNotAppliedException;
import io.legohunter.bricklink.webgateway.session.SessionState;
import io.legohunter.bricklink.webgateway.session.StorageStateParser.InvalidSessionStateException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps the gateway's failures to a small, stable error body. */
@RestControllerAdvice
public class ApiErrorHandler {

    public record ApiError(String code, String message) {
    }

    @ExceptionHandler(TokenException.class)
    public ResponseEntity<ApiError> token(TokenException e) {
        String role = e.role().name();
        return e.configured()
                ? error(HttpStatus.UNAUTHORIZED, role + "_TOKEN_REQUIRED", e.getMessage())
                : error(HttpStatus.SERVICE_UNAVAILABLE, role + "_TOKEN_NOT_CONFIGURED", e.getMessage());
    }

    @ExceptionHandler(InvalidSessionStateException.class)
    public ResponseEntity<ApiError> invalidState(InvalidSessionStateException e) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_SESSION_STATE", e.getMessage());
    }

    /** Callers stop and retry later on SESSION_SIGNED_OUT (a sign-in is needed); the others are temporary. */
    @ExceptionHandler(SessionUnavailableException.class)
    public ResponseEntity<ApiError> session(SessionUnavailableException e) {
        String code = e.state() == SessionState.SIGNED_OUT ? "SESSION_SIGNED_OUT"
                : e.state() == SessionState.RECOVERING ? "SESSION_RECOVERING" : "SESSION_UNKNOWN";
        return error(HttpStatus.SERVICE_UNAVAILABLE, code, e.getMessage());
    }

    @ExceptionHandler(LotGuardRefusedException.class)
    public ResponseEntity<ApiError> guardRefused(LotGuardRefusedException e) {
        return error(HttpStatus.CONFLICT, "LOT_GUARD_REFUSED", e.getMessage());
    }

    @ExceptionHandler(GuardUnavailableException.class)
    public ResponseEntity<ApiError> guardUnavailable(GuardUnavailableException e) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "LOT_GUARD_UNAVAILABLE", e.getMessage());
    }

    @ExceptionHandler(LotNotFoundException.class)
    public ResponseEntity<ApiError> lotNotFound(LotNotFoundException e) {
        return error(HttpStatus.NOT_FOUND, "LOT_NOT_FOUND", e.getMessage());
    }

    @ExceptionHandler(LotNotSupportedException.class)
    public ResponseEntity<ApiError> lotNotSupported(LotNotSupportedException e) {
        return error(HttpStatus.CONFLICT, "LOT_NOT_SUPPORTED", e.getMessage());
    }

    @ExceptionHandler(UnexpectedPageException.class)
    public ResponseEntity<ApiError> unexpectedPage(UnexpectedPageException e) {
        return error(HttpStatus.BAD_GATEWAY, "UNEXPECTED_PAGE", e.getMessage());
    }

    @ExceptionHandler(WriteNotAppliedException.class)
    public ResponseEntity<ApiError> writeNotApplied(WriteNotAppliedException e) {
        return error(HttpStatus.BAD_GATEWAY, "WRITE_NOT_APPLIED", e.getMessage());
    }

    @ExceptionHandler(InvalidRequestException.class)
    public ResponseEntity<ApiError> invalidRequest(InvalidRequestException e) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", e.getMessage());
    }

    @ExceptionHandler(GatewayBusyException.class)
    public ResponseEntity<ApiError> busy(GatewayBusyException e) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "GATEWAY_BUSY", e.getMessage());
    }

    @ExceptionHandler(BrowserException.class)
    public ResponseEntity<ApiError> browser(BrowserException e) {
        return error(HttpStatus.BAD_GATEWAY, "BROWSER_FAILED", e.getMessage());
    }

    private static ResponseEntity<ApiError> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new ApiError(code, message));
    }
}
