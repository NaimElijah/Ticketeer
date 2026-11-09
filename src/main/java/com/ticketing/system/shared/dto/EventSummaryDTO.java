package com.ticketing.system.shared.dto;

import java.time.ZoneId;
import java.util.List;

/**
 * A lightweight event for listings: browse (UC-3) and search results (UC-7). The per-event detail page
 * uses {@code EventDetailDTO}.
 *
 * @param status      the event's status name, such as {@code ON_SALE}
 * @param rating      the event's rating; {@code null} when it has none
 * @param category    the event category name
 * @param location    the venue as {@code "city, country"}
 * @param showDates   the show dates, in wall-clock time at the venue
 * @param timeZone    the zone the {@code showDates} are wall-clock times in (ADR-008)
 * @param minPrice    the lowest zone price; {@code 0} when the event has no zone
 * @param maxPrice    the highest zone price; {@code 0} when the event has no zone
 * @param soldOut     whether the event's status is SOLD_OUT
 * @param artistsNames the artists' names
 */
public record EventSummaryDTO(
    int eventId,
    String name,
    String status,
    Double rating,
    String companyName,
    String category,
    String location,
    List<ShowDateDTO> showDates,
    ZoneId timeZone,
    double minPrice,
    double maxPrice,
    boolean soldOut,
    List<String> artistsNames
) {}
