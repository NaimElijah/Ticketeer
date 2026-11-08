package com.ticketing.system.ui.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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
                DateTimeFormatter.ofPattern("d MMM yyyy · HH:mm", Locale.ENGLISH));

        assertThat(shown).isEqualTo("15 Jul 2026 · 20:00");
    }

    @Test
    void GivenClockAtMiddayUtc_WhenFormatNow_ThenShownAt1500InJerusalem() {
        String shown = displayTime.formatNow(DateTimeFormatter.ofPattern("d MMM yyyy · HH:mm:ss", Locale.ENGLISH));

        assertThat(shown).isEqualTo("15 Jul 2026 · 15:00:00");
    }

    // A "since midnight today" range bound in Jerusalem is 21:00 the day before in UTC.
    @Test
    void GivenMidnightInJerusalem_WhenToRecorded_ThenThePreviousDayAt2100Utc() {
        assertThat(displayTime.toRecorded(LocalDateTime.of(2026, 7, 16, 0, 0)))
                .isEqualTo(LocalDateTime.of(2026, 7, 15, 21, 0));
    }

    // ---- #189: each unit's edges, and Jerusalem in winter (IST, UTC+2) ----

    @ParameterizedTest(name = "{0} ago reads {1}")
    @CsvSource({
        "PT59S, now", "PT1M, 1m", "PT59M59S, 59m", "PT1H, 1h", "PT23H59M, 23h", "PT24H, 1d",
        "P6DT23H, 6d", "P7D, 1w", "P13DT23H, 1w", "P14D, 2w"})
    void GivenAnAgeAtAUnitsEdge_WhenAgeShort_ThenItReadsInTheLargestWholeUnit(String age, String shown) {
        assertThat(displayTime.ageShort(NOW_RECORDED.minus(Duration.parse(age)))).isEqualTo(shown);
    }

    @ParameterizedTest(name = "{0} ago reads {1}")
    @CsvSource({
        "PT23H59M, today", "P1D, yesterday", "P2D, 2 days ago", "P6DT23H, 6 days ago", "P7D, 1 week ago",
        "P8D, 1 week ago", "P14D, 2 weeks ago"})
    void GivenAnAgeAtADaysEdge_WhenAgeInDays_ThenItReadsInWholeDaysOrWeeks(String age, String shown) {
        assertThat(displayTime.ageInDays(NOW_RECORDED.minus(Duration.parse(age)))).isEqualTo(shown);
    }

    @ParameterizedTest(name = "{0} ago reads {1}")
    @CsvSource({
        "PT59S, Just now", "PT1M, 1m", "PT59M, 59m", "PT1H, 1h", "PT23H59M, 23h", "P2D, 2d"})
    void GivenAnAgeAtAUnitsEdge_WhenAgeForNotification_ThenItReadsInTheLargestWholeUnit(String age, String shown) {
        assertThat(displayTime.ageForNotification(NOW_RECORDED.minus(Duration.parse(age)))).isEqualTo(shown);
    }

    @Test
    void GivenPurchaseRecordedAt1700UtcInWinter_WhenFormatted_ThenShownAt1900InJerusalem() {
        String shown = displayTime.format(LocalDateTime.of(2026, 1, 15, 17, 0),
                DateTimeFormatter.ofPattern("d MMM yyyy · HH:mm", Locale.ENGLISH));

        assertThat(shown).isEqualTo("15 Jan 2026 · 19:00");
    }

    @Test
    void GivenMidnightInJerusalemInWinter_WhenToRecorded_ThenThePreviousDayAt2200Utc() {
        assertThat(displayTime.toRecorded(LocalDateTime.of(2026, 1, 16, 0, 0)))
                .isEqualTo(LocalDateTime.of(2026, 1, 15, 22, 0));
    }

    @Test
    void GivenNoBound_WhenToRecorded_ThenNoBound() {
        assertThat(displayTime.toRecorded(null)).isNull();
    }
}
