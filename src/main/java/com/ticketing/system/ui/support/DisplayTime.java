package com.ticketing.system.ui.support;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import org.springframework.stereotype.Component;

/**
 * The UI's single reading of "now": relative ages shown next to messages, invitations and
 * notifications, and the "today" that date-range presets count from. Views receive it by
 * injection, so no view reads the clock itself.
 */
@Component
public class DisplayTime {

    private static final String NO_AGE = "";
    private static final String NO_SENT_DATE = "—";
    private static final int DAYS_PER_WEEK = 7;
    private static final int MINUTES_PER_HOUR = 60;
    private static final int HOURS_PER_DAY = 24;

    private final Clock clock;

    /** Reads the JVM's default clock, as the views did before this class existed. */
    public DisplayTime() {
        this(Clock.systemDefaultZone());
    }

    DisplayTime(Clock clock) {
        this.clock = clock;
    }

    /**
     * Compact age for conversation and message lists.
     *
     * @param when the moment to describe; may be null
     * @return {@code now}, {@code Nm}, {@code Nh}, {@code Nd} or {@code Nw}; empty for null
     */
    public String ageShort(LocalDateTime when) {
        if (when == null) return NO_AGE;
        Duration age = Duration.between(when, now());
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
     * @param when the moment the invitation was sent; may be null
     * @return {@code today}, {@code yesterday}, {@code N days ago} or {@code N weeks ago};
     *         an em dash for null
     */
    public String ageInDays(LocalDateTime when) {
        if (when == null) return NO_SENT_DATE;
        long days = Duration.between(when, now()).toDays();
        if (days <= 0) return "today";
        if (days == 1) return "yesterday";
        if (days < DAYS_PER_WEEK) return days + " days ago";
        long weeks = days / DAYS_PER_WEEK;
        return weeks == 1 ? "1 week ago" : weeks + " weeks ago";
    }

    /**
     * Age for the notification panel.
     *
     * @param when the moment the notification was created; may be null
     * @return {@code Just now}, {@code Nm}, {@code Nh}, {@code Yesterday} or {@code Nd}; empty for
     *         null
     */
    public String ageForNotification(LocalDateTime when) {
        if (when == null) return NO_AGE;
        LocalDateTime now = now();
        long minutes = ChronoUnit.MINUTES.between(when, now);
        if (minutes < 1) return "Just now";
        if (minutes < MINUTES_PER_HOUR) return minutes + "m";
        long hours = ChronoUnit.HOURS.between(when, now);
        if (hours < HOURS_PER_DAY) return hours + "h";
        long days = ChronoUnit.DAYS.between(when, now);
        if (days == 1) return "Yesterday";
        return days + "d";
    }

    /** @return today's date, which the date-range presets ("Today", "Last 7 days", …) count from */
    public LocalDate today() {
        return LocalDate.now(clock);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }
}
