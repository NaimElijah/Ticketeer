package com.ticketing.system.notifications.config;

import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

import lombok.extern.slf4j.Slf4j;

/**
 * Drops a notification the full notification executor cannot queue, and logs it (ADR-004).
 *
 * <p>It never throws, because the submission can happen on the publisher's thread when a notice is
 * published outside a transaction, and a notification must not fail the use case. It never runs the
 * task on the caller either: that would block the use case, and during {@code afterCompletion} the
 * caller's committed transaction is still bound, so the notification's insert would join it and be
 * lost.
 */
@Slf4j
public class DropAndLogRejectionHandler implements RejectedExecutionHandler {

    /**
     * Discards {@code task} without running it.
     *
     * @param task     the notification delivery that could not be queued
     * @param executor the saturated executor, read only for its queue size in the log
     */
    @Override
    public void rejectedExecution(Runnable task, ThreadPoolExecutor executor) {
        log.warn("Notification queue full ({} waiting); dropping a notification", executor.getQueue().size());
    }
}
