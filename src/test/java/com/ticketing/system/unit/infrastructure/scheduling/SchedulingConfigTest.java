package com.ticketing.system.unit.infrastructure.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import com.ticketing.system.bootstrap.scheduling.MarketSelfHealScheduler;
import com.ticketing.system.bootstrap.scheduling.SchedulingConfig;

/**
 * The market self-heal runs on a scheduler of its own (#187 review follow-up 2): its WSEP probe can take
 * about a minute while WSEP hangs, and on the shared scheduler thread that would delay every sweeper.
 * Proven by wiring, not timing: the self-heal names its own single-thread scheduler, and every other
 * {@code @Scheduled} method still resolves Boot's default one.
 */
class SchedulingConfigTest {

    private static final String DEFAULT_TASK_SCHEDULER = "taskScheduler";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
            .withUserConfiguration(SchedulingConfig.class);

    @Test
    void GivenSchedulingConfig_WhenTheContextStarts_ThenTheSelfHealHasItsOwnSingleThreadScheduler() {
        runner.run(context -> {
            ThreadPoolTaskScheduler selfHeal = context.getBean(
                    SchedulingConfig.MARKET_SELF_HEAL_TASK_SCHEDULER, ThreadPoolTaskScheduler.class);

            assertThat(selfHeal.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(1);
            assertThat(selfHeal).isNotSameAs(context.getBean(DEFAULT_TASK_SCHEDULER));
        });
    }

    // Declaring a TaskScheduler bean makes Boot's auto-configured one back off; without a default, every
    // other job would land on the self-heal's thread, the opposite of the point.
    @Test
    void GivenSchedulingConfig_WhenATaskSchedulerIsResolvedByType_ThenItIsTheDefaultNotTheSelfHeals() {
        runner.run(context -> assertThat(context.getBean(TaskScheduler.class))
                .isSameAs(context.getBean(DEFAULT_TASK_SCHEDULER))
                .isNotSameAs(context.getBean(SchedulingConfig.MARKET_SELF_HEAL_TASK_SCHEDULER)));
    }

    @Test
    void GivenTheSelfHealTick_WhenItsScheduleIsRead_ThenItNamesItsOwnScheduler() throws NoSuchMethodException {
        Method tick = MarketSelfHealScheduler.class.getMethod("reopenMarketIfNeeded");

        assertThat(tick.getAnnotation(Scheduled.class).scheduler())
                .isEqualTo(SchedulingConfig.MARKET_SELF_HEAL_TASK_SCHEDULER);
    }
}
