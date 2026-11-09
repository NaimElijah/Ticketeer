package com.ticketing.system.shared.dto;

import java.time.LocalDateTime;

/**
 * One scheduled showing of an event; an event has one or more per II.4.1.1.
 *
 * @param startsAt when the show starts, in wall-clock time at the venue, read in the event's time zone
 *                 (ADR-008)
 * @param endsAt   when the show ends, in the same terms
 */
public record ShowDateDTO(
    LocalDateTime startsAt,
    LocalDateTime endsAt
) {}
