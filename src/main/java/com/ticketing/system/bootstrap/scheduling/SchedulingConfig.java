package com.ticketing.system.bootstrap.scheduling;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Activates Spring's {@code @Scheduled} support in production. The sweeper
 * component ({@link SessionAndOrderSweeper}) depends on it; without this
 * annotation the scheduled method would never fire in production.
 *
 * <p>Disabled in the {@code test} profile so acceptance tests don't kick
 * off a real scheduler thread (tests drive the sweep method directly).
 */
@Configuration
@EnableScheduling
@Profile("!test")
public class SchedulingConfig {
}
