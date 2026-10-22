package com.ticketing.system.ui.support;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

import org.springframework.stereotype.Component;

import com.ticketing.system.shared.config.PlatformTimeProperties;

/**
 * The UI's single reading of "now" and its single translation of recorded times (ADR-008).
 *
 * <p>Recorded timestamps (purchases, messages, invitations, notifications) are UTC wall time read
 * from the injected clock. Relative ages ("5m", "yesterday") compare them with the clock in UTC;
 * everything a user reads as a date or time of day ("today", a purchase date, the start of a date
 * range) is in the platform's home zone ({@code platform.time-zone}). Event show times are not
 * recorded times: they are formatted in the event's own zone by the views that show them.
 */
@Component
public class DisplayTime {

    private static final String NO_AGE = "";
    private static final String NO_SENT_DATE = "—";
    private static final int DAYS_PER_WEEK = 7;
    private static final int MINUTES_PER_HOUR = 60;
    private static final int HOURS_PER_DAY = 24;

    private final Clock clock;
    private final ZoneId displayZone;

    /**
     * @param clock        the platform clock (UTC)
     * @param platformTime the zone dates and times are shown in
     */
    public DisplayTime(Clock clock, PlatformTimeProperties platformTime) {
        this.clock = clock;
        this.displayZone = platformTime.timeZone();
    }

    /**
     * Compact age for conversation and message lists.
     *
     * @param when the recorded moment to describe (UTC wall time); may be null
     * @return {@code now}, {@code Nm}, {@code Nh}, {@code Nd} or {@code Nw}; empty for null
     */
    public String ageShort(LocalDateTime when) {
        if (when == null) return NO_AGE;
        Duration age = Duration.between(when, recordedNow());
        long minutes = age.toMinutes();
        if (minutes < 1) return "now";
        if (minutes < MINUTES_PER_HOUR) return minutes + "m";
        long hours = age.toHours();
        if (hours < HOURS_PER_DAY) return hours + "h";
        long days = age.toDays();
        if (days < DAYS_PER_WEEK) return days + "d";
        return (days / DAYS_PER_WEEK) + "w";
    }

    /**
     * Age in whole days, for appointment invitations.
     *
     * @param when the moment the invitation was sent (UTC wall time); may be null
     * @return {@code today}, {@code yesterday}, {@code N days ago} or {@code N weeks ago};
     *         an em dash for null
     */
    public String ageInDays(LocalDateTime when) {
        if (when == null) return NO_SENT_DATE;
        long days = Duration.between(when, recordedNow()).toDays();
        if (days <= 0) return "today";
        if (days == 1) return "yesterday";
        if (days < DAYS_PER_WEEK) return days + " days ago";
        long weeks = days / DAYS_PER_WEEK;
        return weeks == 1 ? "1 week ago" : weeks + " weeks ago";
    }

    /**
     * Age for the notification panel.
     *
     * @param when the moment the notification was created (UTC wall time); may be null
     * @return {@code Just now}, {@code Nm}, {@code Nh}, {@code Yesterday} or {@code Nd}; empty for
     *         null
     */
    public String ageForNotification(LocalDateTime when) {
        if (when == null) return NO_AGE;
        LocalDateTime now = recordedNow();
        long minutes = ChronoUnit.MINUTES.between(when, now);
        if (minutes < 1) return "Just now";
        if (minutes < MINUTES_PER_HOUR) return minutes + "m";
        long hours = ChronoUnit.HOURS.between(when, now);
        if (hours < HOURS_PER_DAY) return hours + "h";
        long days = ChronoUnit.DAYS.between(when, now);
        if (days == 1) return "Yesterday";
        return days + "d";
    }

    /** @return today's date in the platform zone, which the date-range presets count from */
    public LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), displayZone);
    }

    /**
     * Formats a recorded timestamp in the platform zone.
     *
     * @param recorded  a recorded moment, as UTC wall time; must not be null
     * @param formatter the pattern to render it with
     * @return the moment as it reads on a clock in the platform zone
     */
    public String format(LocalDateTime recorded, DateTimeFormatter formatter) {
        return formatter.format(recorded.atOffset(ZoneOffset.UTC).atZoneSameInstant(displayZone));
    }

    /**
     * Converts a time of day the user means (a range bound such as "midnight today") into the UTC
     * wall time recorded timestamps are compared in.
     *
     * @param displayTime a wall time in the platform zone, or null
     * @return the same moment as UTC wall time, or null for null
     */
    public LocalDateTime toRecorded(LocalDateTime displayTime) {
        if (displayTime == null) return null;
        return displayTime.atZone(displayZone).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    private LocalDateTime recordedNow() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
