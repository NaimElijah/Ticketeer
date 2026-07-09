package com.ticketing.system.shared.dto;

import java.time.LocalDateTime;

// One scheduled showing of an Event. Multiple ShowDates per Event per II.4.1.1.
public record ShowDateDTO(
    LocalDateTime startsAt,
    LocalDateTime endsAt
) {}
