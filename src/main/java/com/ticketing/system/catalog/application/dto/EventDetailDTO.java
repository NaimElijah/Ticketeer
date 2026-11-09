package com.ticketing.system.catalog.application.dto;

import java.time.ZoneId;
import java.util.List;

import com.ticketing.system.catalog.domain.EventCategory;
import com.ticketing.system.catalog.domain.EventStatus;
import com.ticketing.system.catalog.domain.Location;
import com.ticketing.system.catalog.domain.ShowDate;

/**
 * The full detail of an event, for when the catalog summary ({@code EventSummaryDTO}) isn't enough.
 * UC-8 (alongside {@code VenueMapDTO}), UC-19 (an owner viewing their event to edit it).
 *
 * @param eventId       the event's id, as text
 * @param rating        the event's rating from 0 to 5, or {@code null} if unrated
 * @param description   the event's description; {@code null} if it has none, and also in the DTO that
 *                      {@code EventManagementService.getEvent} returns
 * @param location      the venue's location, or {@code null} if the event has no venue map
 * @param companyId     the owning company's id, as text
 * @param showDates     the shows, as wall-clock times at the venue
 * @param timeZone      the zone {@code showDates} are wall-clock times in (ADR-008)
 * @param artistsNames  the lineup
 * @param minPrice      the cheapest ticket price across the event's zones; 0 when it has no venue map or
 *                      no zones
 */
public record EventDetailDTO(
        String eventId,
        String name,
        Double rating,
        String description,
        EventCategory category,
        Location location,
        String companyId,
        String companyName,
        EventStatus status,
        List<ShowDate> showDates,
        ZoneId timeZone,
        List<String> artistsNames,
        double minPrice
) {
}
