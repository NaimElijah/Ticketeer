package com.ticketing.system.ui.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class DisplayTimeTest {

    private static final Instant NOW = Instant.parse("2026-07-15T12:00:00Z");
    private static final LocalDateTime NOW_WALL = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);

    private final DisplayTime displayTime = new DisplayTime(Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void GivenThirtySecondsAgo_WhenAgeShort_ThenNow() {
        assertThat(displayTime.ageShort(NOW_WALL.minusSeconds(30))).isEqualTo("now");
    }

    @Test
    void GivenNinetyMinutesAgo_WhenAgeShort_ThenOneHour() {
        assertThat(displayTime.ageShort(NOW_WALL.minusMinutes(90))).isEqualTo("1h");
    }

    @Test
    void GivenTenDaysAgo_WhenAgeShort_ThenOneWeek() {
        assertThat(displayTime.ageShort(NOW_WALL.minusDays(10))).isEqualTo("1w");
    }

    @Test
    void GivenNoTimestamp_WhenAgeShort_ThenEmpty() {
        assertThat(displayTime.ageShort(null)).isEmpty();
    }

    @Test
    void GivenOneDayAgo_WhenAgeInDays_ThenYesterday() {
        assertThat(displayTime.ageInDays(NOW_WALL.minusDays(1))).isEqualTo("yesterday");
    }

    @Test
    void GivenFifteenDaysAgo_WhenAgeInDays_ThenTwoWeeksAgo() {
        assertThat(displayTime.ageInDays(NOW_WALL.minusDays(15))).isEqualTo("2 weeks ago");
    }

    @Test
    void GivenNoTimestamp_WhenAgeInDays_ThenDash() {
        assertThat(displayTime.ageInDays(null)).isEqualTo("—");
    }

    @Test
    void GivenFiveMinutesAgo_WhenAgeForNotification_ThenFiveMinutes() {
        assertThat(displayTime.ageForNotification(NOW_WALL.minusMinutes(5))).isEqualTo("5m");
    }

    @Test
    void GivenOneDayAgo_WhenAgeForNotification_ThenYesterday() {
        assertThat(displayTime.ageForNotification(NOW_WALL.minusDays(1))).isEqualTo("Yesterday");
    }

    @Test
    void GivenFixedClock_WhenToday_ThenTheClocksDate() {
        assertThat(displayTime.today()).isEqualTo(LocalDate.of(2026, 7, 15));
    }
}
