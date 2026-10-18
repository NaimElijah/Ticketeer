package com.ticketing.system.notifications.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Runs notification delivery off the publisher's thread (ADR-004). {@code NotificationEventListener}
 * consumes notices after commit on the executor defined here, so a slow or failing channel never
 * delays, fails or rolls back the use case that raised the notice.
 */
@Configuration
@EnableAsync
public class NotificationAsyncConfig {

    /** Bean name of the executor that {@code NotificationEventListener} runs on. */
    public static final String NOTIFICATION_EXECUTOR = "notificationExecutor";

    // One thread stores notices in submission order (commit order across transactions); storing one
    // is a single row insert.
    private static final int POOL_SIZE = 1;
    // Bounds memory if storage stalls; beyond it a notice is dropped and logged (ADR-004).
    private static final int QUEUE_CAPACITY = 1_000;
    private static final String THREAD_NAME_PREFIX = "notify-";
    // How long shutdown waits for queued notices before abandoning them.
    private static final int SHUTDOWN_WAIT_SECONDS = 10;

    @Bean(name = NOTIFICATION_EXECUTOR)
    ThreadPoolTaskExecutor notificationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(POOL_SIZE);
        executor.setMaxPoolSize(POOL_SIZE);
        executor.setQueueCapacity(QUEUE_CAPACITY);
        executor.setThreadNamePrefix(THREAD_NAME_PREFIX);
        executor.setRejectedExecutionHandler(new DropAndLogRejectionHandler());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(SHUTDOWN_WAIT_SECONDS);
        return executor;
    }
}
