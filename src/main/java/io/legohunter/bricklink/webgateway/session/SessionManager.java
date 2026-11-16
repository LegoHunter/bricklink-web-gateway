package io.legohunter.bricklink.webgateway.session;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import io.legohunter.bricklink.webgateway.browser.BrowserException;
import io.legohunter.bricklink.webgateway.browser.BrowserSession;
import io.legohunter.bricklink.webgateway.browser.BrowserSession.CookieInfo;
import io.legohunter.bricklink.webgateway.browser.BrowserSession.FetchResult;
import io.legohunter.bricklink.webgateway.browser.ImportedCookie;
import io.legohunter.bricklink.webgateway.browser.OperationQueue;
import io.legohunter.bricklink.webgateway.config.GatewayProperties;
import io.legohunter.bricklink.webgateway.logging.SafeUrl;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Owns the session state: probes it, keeps it alive, and takes an imported session.
 *
 * <p>The state is the outcome of the last probe, except while an import is running
 * ({@link SessionState#RECOVERING}). A probe that could not reach BrickLink gives
 * {@link SessionState#UNKNOWN}, never {@link SessionState#SIGNED_OUT}.
 */
@Service
public class SessionManager {

    private static final Logger log = LoggerFactory.getLogger(SessionManager.class);

    private final BrowserSession browser;
    private final OperationQueue queue;
    private final GatewayProperties properties;
    private final Clock clock;
    private final MeterRegistry meters;

    private final Object lock = new Object();
    private SessionState state = SessionState.UNKNOWN;
    private Instant lastProbeAt;
    private Instant lastSignedInAt;
    private int lastProbeHttpStatus;
    private String lastProbeLocation;
    private Map<SessionLayer, Instant> layerExpiry = new EnumMap<>(SessionLayer.class);
    private Instant lastBrowserUse;

    public SessionManager(BrowserSession browser, OperationQueue queue, GatewayProperties properties, Clock clock,
                          MeterRegistry meters) {
        this.browser = browser;
        this.queue = queue;
        this.properties = properties;
        this.clock = clock;
        this.meters = meters;
        registerGauges();
    }

    public SessionSnapshot snapshot() {
        synchronized (lock) {
            List<SessionSnapshot.Layer> layers = new ArrayList<>();
            for (SessionLayer layer : SessionLayer.values()) {
                layers.add(new SessionSnapshot.Layer(layer, layerExpiry.get(layer)));
            }
            return new SessionSnapshot(state, lastProbeAt, lastSignedInAt, lastProbeHttpStatus, lastProbeLocation,
                    layers, browser.isOpen());
        }
    }

    /**
     * Visits a signed-in page and records where it ended. This is also the keep-alive: the visit is
     * what makes BrickLink re-issue the renewable cookies. Does nothing while an import is running.
     */
    public SessionSnapshot probe() {
        synchronized (lock) {
            if (state == SessionState.RECOVERING) {
                return snapshot();
            }
        }
        queue.run(() -> {
            probeOnBrowserThread();
            return null;
        });
        return snapshot();
    }

    /**
     * The scheduled keep-alive. Skipped when signed out: there is nothing to keep alive, and only an
     * import (which probes on its own) can change that. Still runs from UNKNOWN, to notice BrickLink
     * coming back.
     */
    public void keepAlive() {
        synchronized (lock) {
            if (state == SessionState.SIGNED_OUT || state == SessionState.RECOVERING) {
                return;
            }
        }
        try {
            probe();
        } catch (RuntimeException e) {
            log.warn("Keep-alive did not run: {}", e.getClass().getSimpleName());
        }
    }

    /** Replaces the session with one signed in elsewhere, then probes it. */
    public SessionSnapshot importSession(List<ImportedCookie> cookies) {
        synchronized (lock) {
            state = SessionState.RECOVERING;
        }
        log.info("Session import started: {} cookies", cookies.size());
        try {
            queue.run(() -> {
                browser.replaceCookies(cookies);
                probeOnBrowserThread();
                return null;
            });
        } finally {
            synchronized (lock) {
                // The import failed before its probe could set a state.
                if (state == SessionState.RECOVERING) {
                    state = SessionState.UNKNOWN;
                }
            }
        }
        SessionSnapshot result = snapshot();
        log.info("Session import finished: {}", result.state());
        return result;
    }

    /** Closes the browser after the idle timeout, to free its memory. */
    public void closeBrowserIfIdle() {
        Instant lastUse;
        synchronized (lock) {
            lastUse = lastBrowserUse;
        }
        if (!browser.isOpen() || lastUse == null
                || Duration.between(lastUse, clock.instant()).compareTo(properties.browserIdleTimeout()) < 0) {
            return;
        }
        try {
            queue.run(() -> {
                browser.close();
                return null;
            });
        } catch (RuntimeException e) {
            log.warn("Closing the idle browser did not run: {}", e.getClass().getSimpleName());
        }
    }

    private void probeOnBrowserThread() {
        SessionState outcome;
        int httpStatus = 0;
        String location = SafeUrl.UNPARSEABLE;
        Map<SessionLayer, Instant> expiry = null;
        try {
            FetchResult result = browser.fetch(properties.probeUrl());
            httpStatus = result.httpStatus();
            location = SafeUrl.of(result.finalUrl());
            outcome = ProbeClassifier.classify(result.httpStatus(), result.finalUrl());
            expiry = readLayerExpiry();
        } catch (BrowserException e) {
            log.warn("Probe could not complete: {}", e.getMessage());
            outcome = SessionState.UNKNOWN;
        }
        Instant now = clock.instant();
        SessionState previous;
        synchronized (lock) {
            previous = state;
            state = outcome;
            lastProbeAt = now;
            lastProbeHttpStatus = httpStatus;
            lastProbeLocation = location;
            lastBrowserUse = now;
            if (outcome == SessionState.SIGNED_IN) {
                lastSignedInAt = now;
            }
            if (expiry != null) {
                layerExpiry = expiry;
            }
        }
        meters.counter("bricklink.gateway.session.probes", "outcome", outcome.name()).increment();
        if (previous != outcome) {
            log.info("Session state {} -> {} (HTTP {}, ended at {})", previous, outcome, httpStatus, location);
        }
    }

    private Map<SessionLayer, Instant> readLayerExpiry() {
        Map<SessionLayer, Instant> expiry = new EnumMap<>(SessionLayer.class);
        for (CookieInfo cookie : browser.cookies()) {
            for (SessionLayer layer : SessionLayer.values()) {
                if (layer.cookieName().equals(cookie.name()) && cookie.expiresEpochSeconds() > 0) {
                    expiry.put(layer, Instant.ofEpochSecond((long) cookie.expiresEpochSeconds()));
                }
            }
        }
        return expiry;
    }

    private void registerGauges() {
        for (SessionState value : SessionState.values()) {
            Gauge.builder("bricklink.gateway.session.state", this, manager -> manager.currentState() == value ? 1 : 0)
                    .description("1 for the current session state, 0 for the others")
                    .tag("state", value.name())
                    .register(meters);
        }
        Gauge.builder("bricklink.gateway.session.signed.in.probe.age", this, SessionManager::secondsSinceSignedIn)
                .description("Seconds since a probe last ended signed in; NaN if never since startup")
                .baseUnit("seconds")
                .register(meters);
        for (SessionLayer layer : SessionLayer.values()) {
            Gauge.builder("bricklink.gateway.session.layer.expiry", this, manager -> manager.secondsUntilExpiry(layer))
                    .description("Seconds until the cookie behind this session layer expires; NaN if unknown")
                    .baseUnit("seconds")
                    .tag("layer", layer.name())
                    .register(meters);
        }
    }

    private SessionState currentState() {
        synchronized (lock) {
            return state;
        }
    }

    private double secondsSinceSignedIn() {
        synchronized (lock) {
            return lastSignedInAt == null ? Double.NaN : Duration.between(lastSignedInAt, clock.instant()).toSeconds();
        }
    }

    private double secondsUntilExpiry(SessionLayer layer) {
        synchronized (lock) {
            Instant expiresAt = layerExpiry.get(layer);
            return expiresAt == null ? Double.NaN : Duration.between(clock.instant(), expiresAt).toSeconds();
        }
    }

    @PreDestroy
    void shutdown() {
        queue.shutdown(browser::close);
    }
}
