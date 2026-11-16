package io.legohunter.bricklink.webgateway.api;

import java.io.IOException;
import java.util.List;

import io.legohunter.bricklink.webgateway.browser.ImportedCookie;
import io.legohunter.bricklink.webgateway.session.SessionManager;
import io.legohunter.bricklink.webgateway.session.SessionSnapshot;
import io.legohunter.bricklink.webgateway.session.SessionState;
import io.legohunter.bricklink.webgateway.session.StorageStateParser;
import io.legohunter.bricklink.webgateway.session.StorageStateParser.InvalidSessionStateException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class SessionController {

    private final SessionManager sessions;
    private final TokenAuthorizer tokens;

    public SessionController(SessionManager sessions, TokenAuthorizer tokens) {
        this.sessions = sessions;
        this.tokens = tokens;
    }

    /** The session as last observed. Read-only and open: it does not contact BrickLink and shows no secret. */
    @GetMapping("/session")
    public SessionSnapshot session() {
        return sessions.snapshot();
    }

    /** Probes BrickLink now (read-only there) and returns the result. Needs the caller token. */
    @PostMapping("/session/probe")
    public SessionSnapshot probe(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        tokens.requireCaller(authorization);
        return sessions.probe();
    }

    /**
     * Takes a session exported on the owner's PC (a Playwright storage-state file) and makes it the
     * gateway's session. Needs the admin token. The file is a live credential: it is read from
     * memory, never stored as a file and never logged.
     *
     * @return 200 with the session if it probes as signed in; 422 with the session otherwise
     */
    @PostMapping(path = "/admin/session/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<SessionSnapshot> importSession(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestPart("state") MultipartFile state) {
        tokens.requireAdmin(authorization);
        List<ImportedCookie> cookies;
        try {
            cookies = StorageStateParser.parse(state.getBytes());
        } catch (IOException e) {
            throw new InvalidSessionStateException("The uploaded file could not be read");
        }
        SessionSnapshot result = sessions.importSession(cookies);
        HttpStatus status = result.state() == SessionState.SIGNED_IN ? HttpStatus.OK : HttpStatus.UNPROCESSABLE_ENTITY;
        return ResponseEntity.status(status).body(result);
    }
}
