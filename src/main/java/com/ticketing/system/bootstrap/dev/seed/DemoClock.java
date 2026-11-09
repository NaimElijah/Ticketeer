package com.ticketing.system.bootstrap.dev.seed;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Date anchor for {@code ScenarioRunner}. Captures
 * {@link Clock#instant()} once at construction so the show dates of
 * one seed pass are all offsets from the same "now"; every other timestamp
 * comes from the services' own clock. Each scenario run
 * ({@code seed.mode=reseed} or {@code reset}) creates a
 * fresh {@code DemoClock}, so a reset re-anchors against the
 * current moment without dragging stale offsets along.
 *
 * <p>Not a Spring bean — instantiated explicitly by the orchestrator
 * so the anchor lifecycle matches the seed lifecycle, not the
 * application context lifecycle.
 */
public final class DemoClock {

    private final Instant anchor;

    /**
     * Anchors the clock at the current instant.
     *
     * @param clock read once, here; later changes of {@code clock} do not move the anchor
     */
    public DemoClock(Clock clock) {
        this.anchor = clock.instant();
    }

    /** The anchored "now" — same value for every call on this instance. */
    public Instant now() {
        return anchor;
    }

    /** The anchor minus {@code days} whole days of 24 hours. */
    public Instant minusDays(int days) {
        return anchor.minus(days, ChronoUnit.DAYS);
    }

    /** The anchor plus {@code days} whole days of 24 hours. */
    public Instant plusDays(int days) {
        return anchor.plus(days, ChronoUnit.DAYS);
    }

    /** The anchor minus {@code weeks} weeks of 7 days. */
    public Instant minusWeeks(int weeks) {
        return anchor.minus(weeks * 7L, ChronoUnit.DAYS);
    }

    /** The anchor plus {@code weeks} weeks of 7 days. */
    public Instant plusWeeks(int weeks) {
        return anchor.plus(weeks * 7L, ChronoUnit.DAYS);
    }

    /** The anchor minus {@code months} "months" of a fixed 30 days, not calendar months. */
    public Instant minusMonths(int months) {
        return anchor.minus(months * 30L, ChronoUnit.DAYS);
    }

    /** The anchor plus {@code months} "months" of a fixed 30 days, not calendar months. */
    public Instant plusMonths(int months) {
        return anchor.plus(months * 30L, ChronoUnit.DAYS);
    }

    /** The anchor minus {@code hours} hours. */
    public Instant minusHours(int hours) {
        return anchor.minus(hours, ChronoUnit.HOURS);
    }

    /** The anchor plus {@code hours} hours. */
    public Instant plusHours(int hours) {
        return anchor.plus(hours, ChronoUnit.HOURS);
    }
}
