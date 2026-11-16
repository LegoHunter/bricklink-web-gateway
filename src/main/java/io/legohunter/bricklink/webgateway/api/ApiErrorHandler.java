package io.legohunter.bricklink.webgateway.api;

import io.legohunter.bricklink.webgateway.api.TokenAuthorizer.TokenException;
import io.legohunter.bricklink.webgateway.browser.BrowserException;
import io.legohunter.bricklink.webgateway.browser.OperationQueue.GatewayBusyException;
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
