package com.ticketing.system.bootstrap.scheduling;

import org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Activates Spring's {@code @Scheduled} support in production. The sweeper
 * component ({@link SessionAndOrderSweeper}) depends on it; without this
 * annotation the scheduled method would never fire in production.
 *
 * <p>The market self-heal ({@link MarketSelfHealScheduler}) runs on a single-thread scheduler of its
 * own: while WSEP hangs, its probe takes about two minutes (two handshakes of up to three 20-second
 * attempts each), and on the shared scheduler that would delay every sweeper. Every other
 * {@code @Scheduled} method runs on {@code taskScheduler}. Why, and when to revisit: ADR-020
 * ({@code docs/ADRs/ADR-020-market-self-heal-scheduler-and-reported-reachability.md}).
 *
 * <p>Disabled in the {@code test} profile so acceptance tests don't kick
 * off a real scheduler thread (tests drive the sweep method directly).
 */
@Configuration
@EnableScheduling
@Profile("!test")
public class SchedulingConfig {

    /** Bean name of the scheduler {@link MarketSelfHealScheduler} runs on, apart from every other job. */
    public static final String MARKET_SELF_HEAL_TASK_SCHEDULER = "marketSelfHealTaskScheduler";

    private static final String DEFAULT_TASK_SCHEDULER = "taskScheduler";
    private static final String MARKET_SELF_HEAL_THREAD_PREFIX = "market-self-heal-";
    private static final int MARKET_SELF_HEAL_THREADS = 1;

    /**
     * The scheduler every other {@code @Scheduled} method runs on, built as Boot builds its default from
     * {@code spring.task.scheduling.*}. Declared here because Boot's auto-configured one backs off as soon
     * as any {@code TaskScheduler} bean exists; {@code @Primary} keeps it the one an unqualified
     * {@code @Scheduled} method or a by-type injection resolves.
     */
    @Bean(name = DEFAULT_TASK_SCHEDULER)
    @Primary
    ThreadPoolTaskScheduler taskScheduler(ThreadPoolTaskSchedulerBuilder builder) {
        return builder.build();
    }

    /** The self-heal's own scheduler; {@link MarketSelfHealScheduler} names it in its {@code @Scheduled}. */
    @Bean(name = MARKET_SELF_HEAL_TASK_SCHEDULER)
    ThreadPoolTaskScheduler marketSelfHealTaskScheduler(ThreadPoolTaskSchedulerBuilder builder) {
        return builder.poolSize(MARKET_SELF_HEAL_THREADS).threadNamePrefix(MARKET_SELF_HEAL_THREAD_PREFIX).build();
    }
}
