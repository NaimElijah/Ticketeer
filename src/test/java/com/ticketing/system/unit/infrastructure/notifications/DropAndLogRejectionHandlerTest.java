package com.ticketing.system.unit.infrastructure.notifications;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import com.ticketing.system.notifications.config.DropAndLogRejectionHandler;

/**
 * ADR-004: when the notification executor is saturated, a delivery is dropped. Submitting it must not
 * throw into the publisher, and it must not run on the publisher's thread.
 */
class DropAndLogRejectionHandlerTest {

    private static final long WAIT_SECONDS = 5;

    private final CountDownLatch releaseWorker = new CountDownLatch(1);
    private final CountDownLatch workerBusy = new CountDownLatch(1);
    private ThreadPoolTaskExecutor executor;

    @BeforeEach
    void saturateASingleThreadExecutorWithAOneSlotQueue() throws InterruptedException {
        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(1);
        executor.setRejectedExecutionHandler(new DropAndLogRejectionHandler());
        executor.initialize();
        executor.submit(() -> {
            workerBusy.countDown();
            awaitQuietly(releaseWorker);
        });
        assertTrue(workerBusy.await(WAIT_SECONDS, TimeUnit.SECONDS));
        executor.submit(() -> { });   // fills the only queue slot
    }

    @AfterEach
    void shutDown() {
        releaseWorker.countDown();
        executor.shutdown();
    }

    @Test
    void GivenSaturatedExecutor_WhenANotificationIsSubmitted_ThenItIsDroppedWithoutThrowingOrRunningOnTheCaller()
            throws InterruptedException {
        AtomicBoolean ran = new AtomicBoolean(false);

        assertDoesNotThrow(() -> executor.submit(() -> ran.set(true)));

        releaseWorker.countDown();
        executor.getThreadPoolExecutor().shutdown();
        assertTrue(executor.getThreadPoolExecutor().awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS));
        assertFalse(ran.get(), "a dropped notification must never run, on the caller or later");
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
