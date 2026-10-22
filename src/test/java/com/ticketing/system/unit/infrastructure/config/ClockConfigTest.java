package com.ticketing.system.unit.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import com.ticketing.system.shared.config.SecurityConfig;

/**
 * ADR-008: recorded timestamps are {@code LocalDateTime.now(clock)}, which is UTC wall time only while
 * the one Clock bean ticks in UTC. A bean in another zone would silently shift every record.
 */
class ClockConfigTest {

    @Test
    void GivenTheClockBean_WhenCreated_ThenItTicksInUtc() {
        assertThat(new SecurityConfig().systemClock().getZone()).isEqualTo(ZoneOffset.UTC);
    }
}
