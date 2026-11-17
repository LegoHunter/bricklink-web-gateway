package io.legohunter.bricklink.webgateway.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import io.legohunter.bricklink.webgateway.browser.BrowserException;
import io.legohunter.bricklink.webgateway.browser.BrowserSession.CookieInfo;
import io.legohunter.bricklink.webgateway.browser.BrowserSession.FetchResult;
import io.legohunter.bricklink.webgateway.browser.FakeBrowserSession;
import io.legohunter.bricklink.webgateway.browser.ImportedCookie;
import io.legohunter.bricklink.webgateway.browser.OperationQueue;
import io.legohunter.bricklink.webgateway.config.GatewayProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SessionManagerTest {

    private static final FetchResult SIGNED_IN_PAGE = new FetchResult(200, "https://www.bricklink.com/my.asp");
    private static final FetchResult SIGN_IN_PAGE =
            new FetchResult(200, "https://identity.lego.com/en-US/login?code=SECRET-CODE");

    private final FakeBrowserSession browser = new FakeBrowserSession();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-16T12:00:00Z"));
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final OperationQueue queue = new OperationQueue(Duration.ZERO, Duration.ofSeconds(10));
    private final GatewayProperties properties = new GatewayProperties("https://www.bricklink.com", "/my.asp",
            Path.of("unused"), "", true, Duration.ZERO, Duration.ofMinutes(5), Duration.ofSeconds(10),
            Duration.ofSeconds(10), "", "", new GatewayProperties.KeepAlive(false, Duration.ofHours(1), Duration.ZERO),
            new GatewayProperties.Guard(false, "C"));
    private final SessionManager sessions = new SessionManager(browser, queue, properties, clock, meters);

    @AfterEach
    void stop() {
        sessions.shutdown();
    }

    @Test
    void startsUnknownWithoutContactingBricklink() {
        SessionSnapshot snapshot = sessions.snapshot();

        assertThat(snapshot.state()).isEqualTo(SessionState.UNKNOWN);
        assertThat(snapshot.lastProbeAt()).isNull();
        assertThat(browser.fetches).isZero();
    }

    @Test
    void probeEndingOnABricklinkPageIsSignedIn() {
        browser.nextResult = SIGNED_IN_PAGE;
        browser.jar = List.of(
                new CookieInfo("bricklink.lego-account.refresh-token", ".bricklink.com", 1_800_000_000),
                new CookieInfo("id.a", "identity.lego.com", 1_810_000_000),
                new CookieInfo("ASPSESSIONID", "www.bricklink.com", -1));

        SessionSnapshot snapshot = sessions.probe();

        assertThat(snapshot.state()).isEqualTo(SessionState.SIGNED_IN);
        assertThat(snapshot.lastProbeAt()).isEqualTo(clock.instant());
        assertThat(snapshot.lastSignedInAt()).isEqualTo(clock.instant());
        assertThat(snapshot.lastProbeHttpStatus()).isEqualTo(200);
        assertThat(snapshot.lastProbeLocation()).isEqualTo("www.bricklink.com/my.asp");
        assertThat(snapshot.layers()).extracting(SessionSnapshot.Layer::layer, SessionSnapshot.Layer::expiresAt)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(SessionLayer.BRICKLINK_TOKEN, Instant.ofEpochSecond(1_800_000_000)),
                        org.assertj.core.groups.Tuple.tuple(SessionLayer.BRICKLINK_PORTAL_SESSION, null),
                        org.assertj.core.groups.Tuple.tuple(SessionLayer.LEGO_ACCOUNT, Instant.ofEpochSecond(1_810_000_000)));
    }

    @Test
    void probeEndingOnTheSignInRouteIsSignedOutAndHidesTheQueryString() {
        browser.nextResult = SIGN_IN_PAGE;

        SessionSnapshot snapshot = sessions.probe();

        assertThat(snapshot.state()).isEqualTo(SessionState.SIGNED_OUT);
        assertThat(snapshot.lastSignedInAt()).isNull();
        assertThat(snapshot.lastProbeLocation()).isEqualTo("identity.lego.com/en-US/login");
        assertThat(snapshot.toString()).doesNotContain("SECRET-CODE");
    }

    @Test
    void anOutageIsUnknownNotSignedOut() {
        sessions.probe();
        assertThat(sessions.snapshot().state()).isEqualTo(SessionState.SIGNED_IN);
        Instant signedInAt = clock.instant();

        clock.advance(Duration.ofHours(1));
        browser.nextFailure = new BrowserException("Browser request failed: TimeoutError");
        SessionSnapshot afterOutage = sessions.probe();

        assertThat(afterOutage.state()).isEqualTo(SessionState.UNKNOWN);
        assertThat(afterOutage.lastProbeHttpStatus()).isZero();
        assertThat(afterOutage.lastSignedInAt()).isEqualTo(signedInAt);

        browser.nextFailure = null;
        browser.nextResult = new FetchResult(503, "https://www.bricklink.com/my.asp");
        assertThat(sessions.probe().state()).isEqualTo(SessionState.UNKNOWN);

        browser.nextResult = SIGNED_IN_PAGE;
        assertThat(sessions.probe().state()).isEqualTo(SessionState.SIGNED_IN);
    }

    @Test
    void keepAliveVisitsWhileSignedInOrUnknownButNotWhileSignedOut() {
        sessions.keepAlive();
        assertThat(browser.fetches).isEqualTo(1);
        assertThat(sessions.snapshot().state()).isEqualTo(SessionState.SIGNED_IN);

        sessions.keepAlive();
        assertThat(browser.fetches).isEqualTo(2);

        browser.nextResult = SIGN_IN_PAGE;
        sessions.keepAlive();
        assertThat(sessions.snapshot().state()).isEqualTo(SessionState.SIGNED_OUT);
        assertThat(browser.fetches).isEqualTo(3);

        // Signed out: nothing to keep alive, so no more visits until a session is imported.
        sessions.keepAlive();
        sessions.keepAlive();
        assertThat(browser.fetches).isEqualTo(3);

        // A manual probe still works.
        sessions.probe();
        assertThat(browser.fetches).isEqualTo(4);
    }

    @Test
    void importReplacesTheCookiesThenProbes() {
        browser.nextResult = SIGN_IN_PAGE;
        sessions.probe();
        browser.calls.clear();
        browser.nextResult = SIGNED_IN_PAGE;
        List<ImportedCookie> cookies = List.of(
                new ImportedCookie("bricklink.lego-account.refresh-token", "V", ".bricklink.com", "/", 1_800_000_000, true, true, "Lax"));

        SessionSnapshot snapshot = sessions.importSession(cookies);

        assertThat(browser.calls).containsExactly("replaceCookies", "fetch");
        assertThat(browser.imported).isSameAs(cookies);
        assertThat(snapshot.state()).isEqualTo(SessionState.SIGNED_IN);
    }

    @Test
    void importOfASessionThatIsNotSignedInReportsSignedOut() {
        browser.nextResult = SIGN_IN_PAGE;

        SessionSnapshot snapshot = sessions.importSession(List.of());

        assertThat(snapshot.state()).isEqualTo(SessionState.SIGNED_OUT);
    }

    @Test
    void aFailedImportDoesNotLeaveTheStateRecovering() {
        FakeBrowserSession failing = new FakeBrowserSession() {
            @Override
            public void replaceCookies(List<ImportedCookie> cookies) {
                throw new BrowserException("Importing the cookies failed");
            }
        };
        OperationQueue ownQueue = new OperationQueue(Duration.ZERO, Duration.ofSeconds(10));
        SessionManager manager = new SessionManager(failing, ownQueue, properties, clock, new SimpleMeterRegistry());
        try {
            assertThatThrownBy(() -> manager.importSession(List.of())).isInstanceOf(BrowserException.class);
            assertThat(manager.snapshot().state()).isEqualTo(SessionState.UNKNOWN);
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void closesTheBrowserOnlyAfterTheIdleTimeout() {
        sessions.probe();
        assertThat(browser.open).isTrue();

        clock.advance(Duration.ofMinutes(4));
        sessions.closeBrowserIfIdle();
        assertThat(browser.open).isTrue();

        clock.advance(Duration.ofMinutes(2));
        sessions.closeBrowserIfIdle();
        assertThat(browser.open).isFalse();
        assertThat(browser.closes).isEqualTo(1);

        // Already closed: nothing more to do.
        sessions.closeBrowserIfIdle();
        assertThat(browser.closes).isEqualTo(1);
    }

    @Test
    void publishesStateAndExpiryMetrics() {
        browser.jar = List.of(new CookieInfo("id.a", "identity.lego.com", clock.instant().getEpochSecond() + 86_400));
        sessions.probe();

        assertThat(meters.get("bricklink.gateway.session.state").tag("state", "SIGNED_IN").gauge().value()).isEqualTo(1);
        assertThat(meters.get("bricklink.gateway.session.state").tag("state", "SIGNED_OUT").gauge().value()).isZero();
        assertThat(meters.get("bricklink.gateway.session.layer.expiry").tag("layer", "LEGO_ACCOUNT").gauge().value())
                .isEqualTo(86_400);
        assertThat(meters.get("bricklink.gateway.session.layer.expiry").tag("layer", "BRICKLINK_TOKEN").gauge().value())
                .isNaN();
        assertThat(meters.get("bricklink.gateway.session.probes").tag("outcome", "SIGNED_IN").counter().count()).isEqualTo(1);

        clock.advance(Duration.ofMinutes(10));
        assertThat(meters.get("bricklink.gateway.session.signed.in.probe.age").gauge().value()).isEqualTo(600);
    }

    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
