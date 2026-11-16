package io.legohunter.bricklink.webgateway.session;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** The scheduled keep-alive visit and the idle-browser check. */
@Component
@ConditionalOnProperty(name = "bricklink.web-gateway.keep-alive.enabled", havingValue = "true", matchIfMissing = true)
public class SessionScheduler {

    private final SessionManager sessions;

    public SessionScheduler(SessionManager sessions) {
        this.sessions = sessions;
    }

    @Scheduled(fixedDelayString = "${bricklink.web-gateway.keep-alive.interval:60m}",
            initialDelayString = "${bricklink.web-gateway.keep-alive.initial-delay:30s}")
    public void keepAlive() {
        sessions.keepAlive();
    }

    @Scheduled(fixedDelayString = "1m", initialDelayString = "1m")
    public void closeIdleBrowser() {
        sessions.closeBrowserIfIdle();
    }
}
