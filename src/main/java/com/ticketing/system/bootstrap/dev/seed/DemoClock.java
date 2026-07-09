package com.ticketing.system.bootstrap.dev.seed;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Date anchor for {@code ScenarioRunner}. Captures
 * {@link Clock#instant()} once at construction so every timestamp
 * produced in a single seed pass is computed relative to the same
 * "now". Each {@code seed.mode=wipe} run creates a
 * fresh {@code DemoClock}, so a reset re-anchors against the
 * current moment without dragging stale offsets along.
 *
 * <p>Not a Spring bean — instantiated explicitly by the orchestrator
 * so the anchor lifecycle matches the seed lifecycle, not the
 * application context lifecycle.
 */
public final class DemoClock {

    private final Instant anchor;

    public DemoClock(Clock clock) {
        this.anchor = clock.instant();
    }

    /** The anchored "now" — same value for every call on this instance. */
    public Instant now() {
        return anchor;
    }

    public Instant minusDays(int days) {
        return anchor.minus(days, ChronoUnit.DAYS);
    }

    public Instant plusDays(int days) {
        return anchor.plus(days, ChronoUnit.DAYS);
    }

    public Instant minusWeeks(int weeks) {
        return anchor.minus(weeks * 7L, ChronoUnit.DAYS);
    }

    public Instant plusWeeks(int weeks) {
        return anchor.plus(weeks * 7L, ChronoUnit.DAYS);
    }

    public Instant minusMonths(int months) {
        return anchor.minus(months * 30L, ChronoUnit.DAYS);
    }

    public Instant plusMonths(int months) {
        return anchor.plus(months * 30L, ChronoUnit.DAYS);
    }

    public Instant minusHours(int hours) {
        return anchor.minus(hours, ChronoUnit.HOURS);
    }

    public Instant plusHours(int hours) {
        return anchor.plus(hours, ChronoUnit.HOURS);
    }
}
