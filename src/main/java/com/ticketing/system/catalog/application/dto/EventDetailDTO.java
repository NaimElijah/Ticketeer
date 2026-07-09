package com.ticketing.system.catalog.application.dto;

import java.util.List;

import com.ticketing.system.catalog.domain.EventCategory;
import com.ticketing.system.catalog.domain.EventStatus;
import com.ticketing.system.catalog.domain.Location;
import com.ticketing.system.catalog.domain.ShowDate;

// Full Event detail — used when the catalog summary (EventSummaryDTO) isn't enough.
// UC-8 (alongside VenueMapDTO), UC-19 (Owner viewing their event for edit).
public record EventDetailDTO(
        String eventId,
        String name,
        Double rating,
        String description,
        EventCategory category,
        Location location,
        String companyId,
        String companyName,
        EventStatus status, // event status
        List<ShowDate> showDates,
        List<String> artistsNames, // lineup (Event.getArtistsNames())
        double minPrice // cheapest ticket price across the event's zones (0 when it has no venue/zones)
) {
}
