package com.ticketing.system.catalog.domain;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import com.ticketing.system.shared.InvariantChecked;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

// V3: an @Embeddable value object — one row in an Event's show_dates element collection. Its times
// are wall-clock times at the venue; they mean an instant only together with the event's time zone
// (ADR-008). A protected no-arg ctor lets Hibernate instantiate it.
@Embeddable
public class ShowDate implements InvariantChecked {
    @Column(name = "start_time", nullable = false)
    private LocalDateTime startTime;
    @Column(name = "end_time", nullable = false)
    private LocalDateTime endTime;

    /** For JPA only — do not call from application code. */
    protected ShowDate() { }

    /**
     * A show from {@code startTime} to {@code endTime}, both wall-clock times at the venue. A show in
     * the past is still a valid show: whether a new schedule may start in the past is decided by
     * {@link #requireStartsAfter(Instant, ZoneId)} where a clock and the event's zone are known.
     *
     * @throws IllegalArgumentException if {@code endTime} is not after {@code startTime}
     * @throws IllegalStateException    if either time is null
     */
    public ShowDate(LocalDateTime startTime, LocalDateTime endTime) {
        if (startTime != null && endTime != null && !endTime.isAfter(startTime)) {
            throw new IllegalArgumentException("End time must be after start time");
        }
        this.startTime = startTime;
        this.endTime = endTime;
        checkInvariants();
    }

    /** Perpetual structural invariants (non-null, ordering). */
    @Override
    public void checkInvariants() {
        if (startTime == null) {
            throw new IllegalStateException("ShowDate invariant violated: startTime must not be null");
        }
        if (endTime == null) {
            throw new IllegalStateException("ShowDate invariant violated: endTime must not be null");
        }
        if (!endTime.isAfter(startTime)) {
            throw new IllegalStateException("ShowDate invariant violated: endTime must be after startTime");
        }
    }

    public LocalDateTime getStartTime() {
        return startTime;
    }

    public LocalDateTime getEndTime() {
        return endTime;
    }

    /** @return the instant the show starts, reading its wall-clock start time in {@code timeZone} */
    public Instant startsAt(ZoneId timeZone) {
        return startTime.atZone(timeZone).toInstant();
    }

    /** @return the instant the show ends, reading its wall-clock end time in {@code timeZone} */
    public Instant endsAt(ZoneId timeZone) {
        return endTime.atZone(timeZone).toInstant();
    }

    /**
     * The precondition for scheduling this show: it must start after {@code now} at the venue.
     *
     * @throws IllegalArgumentException if the show starts at or before {@code now}
     */
    public void requireStartsAfter(Instant now, ZoneId timeZone) {
        if (!startsAt(timeZone).isAfter(now)) {
            throw new IllegalArgumentException("Start time must be in the future");
        }
    }
}
