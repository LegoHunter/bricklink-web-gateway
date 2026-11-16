package io.legohunter.bricklink.webgateway.browser;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import io.legohunter.bricklink.webgateway.config.GatewayProperties;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Runs every browser operation on one thread, one at a time, with a minimum pause between them.
 *
 * <p>One thread because Playwright objects may only be used from the thread that created them; one
 * at a time because there is one browser session; the pause keeps the gateway's traffic to BrickLink
 * slow and regular.
 */
@Component
public class OperationQueue {

    private static final int MAX_WAITING = 20;

    private final ThreadPoolExecutor executor;
    private final Duration minimumGap;
    private final Duration operationTimeout;

    // Only touched on the worker thread.
    private long lastFinishedNanos;
    private boolean ranBefore;

    @Autowired
    public OperationQueue(GatewayProperties properties) {
        this(properties.minRequestGap(), properties.operationTimeout());
    }

    public OperationQueue(Duration minimumGap, Duration operationTimeout) {
        this.minimumGap = minimumGap;
        this.operationTimeout = operationTimeout;
        this.executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(MAX_WAITING), runnable -> {
                    Thread thread = new Thread(runnable, "bricklink-browser");
                    thread.setDaemon(true);
                    return thread;
                });
    }

    /**
     * Queues an operation and waits for its result.
     *
     * @throws GatewayBusyException if too many operations are waiting, or this one did not finish in time
     */
    public <T> T run(Supplier<T> operation) {
        Future<T> future;
        try {
            future = executor.submit(() -> {
                pause();
                try {
                    return operation.get();
                } finally {
                    lastFinishedNanos = System.nanoTime();
                    ranBefore = true;
                }
            });
        } catch (RejectedExecutionException e) {
            throw new GatewayBusyException("Too many operations are waiting");
        }
        try {
            return future.get(operationTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // A queued operation is dropped; one already running is left to finish on its own.
            future.cancel(false);
            throw new GatewayBusyException("The operation did not finish in time");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(false);
            throw new GatewayBusyException("Interrupted while waiting for the operation");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(e.getCause());
        }
    }

    private void pause() {
        if (!ranBefore) {
            return;
        }
        long waitNanos = minimumGap.toNanos() - (System.nanoTime() - lastFinishedNanos);
        if (waitNanos > 0) {
            try {
                TimeUnit.NANOSECONDS.sleep(waitNanos);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Runs a last task (closing the browser) on the worker thread, then stops it. */
    public void shutdown(Runnable lastTask) {
        try {
            executor.submit(lastTask).get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            // Shutting down anyway.
        }
        executor.shutdownNow();
    }

    @PreDestroy
    void stop() {
        executor.shutdownNow();
    }

    /** The queue is full or the operation timed out. Temporary: the caller may retry later. */
    public static class GatewayBusyException extends RuntimeException {
        public GatewayBusyException(String message) {
            super(message);
        }
    }
}
