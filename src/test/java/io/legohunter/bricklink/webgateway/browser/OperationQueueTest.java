package io.legohunter.bricklink.webgateway.browser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.legohunter.bricklink.webgateway.browser.OperationQueue.GatewayBusyException;
import org.junit.jupiter.api.Test;

class OperationQueueTest {

    @Test
    void runsEveryOperationOnTheSameThread() {
        OperationQueue queue = new OperationQueue(Duration.ZERO, Duration.ofSeconds(5));
        try {
            String first = queue.run(() -> Thread.currentThread().getName());
            String second = queue.run(() -> Thread.currentThread().getName());

            assertThat(first).isEqualTo("bricklink-browser").isEqualTo(second);
            assertThat(first).isNotEqualTo(Thread.currentThread().getName());
        } finally {
            queue.stop();
        }
    }

    @Test
    void neverRunsTwoOperationsAtOnce() throws Exception {
        OperationQueue queue = new OperationQueue(Duration.ZERO, Duration.ofSeconds(5));
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch done = new CountDownLatch(2);
        try {
            for (String name : List.of("a", "b")) {
                new Thread(() -> {
                    queue.run(() -> {
                        events.add("start");
                        sleep(100);
                        events.add("end");
                        return null;
                    });
                    done.countDown();
                }).start();
            }
            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(events).containsExactly("start", "end", "start", "end");
        } finally {
            queue.stop();
        }
    }

    @Test
    void leavesTheMinimumGapBetweenOperations() {
        OperationQueue queue = new OperationQueue(Duration.ofMillis(300), Duration.ofSeconds(5));
        try {
            long firstEnd = queue.run(System::nanoTime);
            long secondStart = queue.run(System::nanoTime);

            assertThat(Duration.ofNanos(secondStart - firstEnd)).isGreaterThanOrEqualTo(Duration.ofMillis(290));
        } finally {
            queue.stop();
        }
    }

    @Test
    void passesTheOperationsFailureToTheCaller() {
        OperationQueue queue = new OperationQueue(Duration.ZERO, Duration.ofSeconds(5));
        try {
            assertThatThrownBy(() -> queue.run(() -> {
                throw new BrowserException("boom");
            })).isInstanceOf(BrowserException.class).hasMessage("boom");
            // The worker survives a failed operation.
            assertThat(queue.run(() -> "ok")).isEqualTo("ok");
        } finally {
            queue.stop();
        }
    }

    @Test
    void givesUpWhenAnOperationTakesTooLong() {
        OperationQueue queue = new OperationQueue(Duration.ZERO, Duration.ofMillis(100));
        try {
            assertThatThrownBy(() -> queue.run(() -> {
                sleep(1_000);
                return null;
            })).isInstanceOf(GatewayBusyException.class);
        } finally {
            queue.stop();
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
