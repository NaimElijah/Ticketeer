package com.ticketing.system.ui.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import org.junit.jupiter.api.Test;

import com.ticketing.system.shared.config.PlatformTimeProperties;

class DisplayTimeTest {

    private static final Instant NOW = Instant.parse("2026-07-15T12:00:00Z");
    // Recorded timestamps are UTC wall time (ADR-008).
    private static final LocalDateTime NOW_RECORDED = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);
    private static final PlatformTimeProperties JERUSALEM = new PlatformTimeProperties(ZoneId.of("Asia/Jerusalem"));

    private final DisplayTime displayTime = at(NOW);

    private static DisplayTime at(Instant instant) {
        return new DisplayTime(Clock.fixed(instant, ZoneOffset.UTC), JERUSALEM);
    }

    @Test
    void GivenThirtySecondsAgo_WhenAgeShort_ThenNow() {
        assertThat(displayTime.ageShort(NOW_RECORDED.minusSeconds(30))).isEqualTo("now");
    }

    @Test
    void GivenNinetyMinutesAgo_WhenAgeShort_ThenOneHour() {
        assertThat(displayTime.ageShort(NOW_RECORDED.minusMinutes(90))).isEqualTo("1h");
    }

    @Test
    void GivenTenDaysAgo_WhenAgeShort_ThenOneWeek() {
        assertThat(displayTime.ageShort(NOW_RECORDED.minusDays(10))).isEqualTo("1w");
    }

    @Test
    void GivenNoTimestamp_WhenAgeShort_ThenEmpty() {
        assertThat(displayTime.ageShort(null)).isEmpty();
    }

    @Test
    void GivenOneDayAgo_WhenAgeInDays_ThenYesterday() {
        assertThat(displayTime.ageInDays(NOW_RECORDED.minusDays(1))).isEqualTo("yesterday");
    }

    @Test
    void GivenFifteenDaysAgo_WhenAgeInDays_ThenTwoWeeksAgo() {
        assertThat(displayTime.ageInDays(NOW_RECORDED.minusDays(15))).isEqualTo("2 weeks ago");
    }

    @Test
    void GivenNoTimestamp_WhenAgeInDays_ThenDash() {
        assertThat(displayTime.ageInDays(null)).isEqualTo("—");
    }

    @Test
    void GivenFiveMinutesAgo_WhenAgeForNotification_ThenFiveMinutes() {
        assertThat(displayTime.ageForNotification(NOW_RECORDED.minusMinutes(5))).isEqualTo("5m");
    }

    @Test
    void GivenOneDayAgo_WhenAgeForNotification_ThenYesterday() {
        assertThat(displayTime.ageForNotification(NOW_RECORDED.minusDays(1))).isEqualTo("Yesterday");
    }

    @Test
    void GivenMiddayUtc_WhenToday_ThenTheSameDateInJerusalem() {
        assertThat(displayTime.today()).isEqualTo(LocalDate.of(2026, 7, 15));
    }

    // 22:30Z on 15 July is 01:30 on 16 July in Jerusalem (IDT, UTC+3): "today" is the local date.
    @Test
    void GivenClockAt2230Utc_WhenToday_ThenTheNextDayInJerusalem() {
        assertThat(at(Instant.parse("2026-07-15T22:30:00Z")).today()).isEqualTo(LocalDate.of(2026, 7, 16));
    }

    @Test
    void GivenPurchaseRecordedAt1700Utc_WhenFormatted_ThenShownAt2000InJerusalem() {
        String shown = displayTime.format(LocalDateTime.of(2026, 7, 15, 17, 0),
                DateTimeFormatter.ofPattern("d MMM yyyy · HH:mm"));

        assertThat(shown).isEqualTo("15 Jul 2026 · 20:00");
    }

    // A "since midnight today" range bound in Jerusalem is 21:00 the day before in UTC.
    @Test
    void GivenMidnightInJerusalem_WhenToRecorded_ThenThePreviousDayAt2100Utc() {
        assertThat(displayTime.toRecorded(LocalDateTime.of(2026, 7, 16, 0, 0)))
                .isEqualTo(LocalDateTime.of(2026, 7, 15, 21, 0));
    }

    @Test
    void GivenNoBound_WhenToRecorded_ThenNoBound() {
        assertThat(displayTime.toRecorded(null)).isNull();
    }
}
