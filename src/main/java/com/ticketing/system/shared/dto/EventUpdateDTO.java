package com.ticketing.system.shared.dto;

import java.time.ZoneId;
import java.util.List;

// Input to EventManagementService.editEventDetails() (UC-19).
// All fields nullable — represents a partial update; null means "leave this field alone".
// The service converts location/showDates into their domain equivalents and the Event
// aggregate applies the II.3.5.2 state rules (DRAFT/SCHEDULED only).
public record EventUpdateDTO(
    String eventId,
    String name,                  // nullable
    String description,           // nullable
    String category,              // nullable
    LocationDTO location,         // nullable
    ZoneId timeZone,              // nullable — the zone the show dates are read in (ADR-008)
    List<ShowDateDTO> showDates   // nullable — null means leave alone; non-empty replaces the schedule
) {}
