package com.ticketing.system.notifications.config;

import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;

import lombok.extern.slf4j.Slf4j;

/**
 * Drops a notification the full notification executor cannot queue, and logs it (ADR-004).
 *
 * <p>It never throws, because the submission can happen on the publisher's thread when a notice is
 * published outside a transaction, and a notification must not fail the use case. It never runs the
 * task on the caller either: that would block the use case, and during {@code afterCompletion} the
 * caller's committed transaction is still bound, so the notification's insert would join it and be
 * lost.
 *
 * <p>The executor also rejects work submitted after it has shut down, which is logged as such. An
 * overflow logs its first drop and then a running total every hundred drops, so a
 * sustained spike doesn't flood the log.
 */
@Slf4j
public class DropAndLogRejectionHandler implements RejectedExecutionHandler {

    private static final long LOG_EVERY_N_DROPS = 100;

    private final AtomicLong droppedForOverflow = new AtomicLong();

    /**
     * Discards {@code task} without running it.
     *
     * @param task     the notification delivery that could not be queued
     * @param executor the rejecting executor, read only for its state and queue size in the log
     */
    @Override
    public void rejectedExecution(Runnable task, ThreadPoolExecutor executor) {
        if (executor.isShutdown()) {
            log.warn("Notification executor is shut down; dropping a notification");
            return;
        }
        long dropped = droppedForOverflow.incrementAndGet();
        if (dropped == 1 || dropped % LOG_EVERY_N_DROPS == 0) {
            log.warn("Notification queue full ({} waiting); {} notification(s) dropped since startup",
                    executor.getQueue().size(), dropped);
        }
    }
}
