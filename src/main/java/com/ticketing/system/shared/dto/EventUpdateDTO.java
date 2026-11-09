package com.ticketing.system.shared.dto;

import java.time.ZoneId;
import java.util.List;

/**
 * Input to {@code EventManagementService.editEventDetails} (UC-19): a partial update, in which a
 * {@code null} component means "leave this field alone". The service converts location and
 * showDates into their domain equivalents and the Event aggregate applies the II.3.5.2 state rules
 * (DRAFT or SCHEDULED only). A changed schedule or time zone must still leave every show starting in
 * the future; otherwise the update is refused with {@code IllegalArgumentException}.
 *
 * @param eventId     the event's id, as text; text that is not a number is refused with
 *                    {@code IllegalArgumentException}
 * @param name        the new name; {@code null} or blank leaves the name alone
 * @param description the new description
 * @param category    an event category name, case aside; {@code null} or blank leaves the category
 *                    alone, and an unknown name is refused
 * @param location    the new venue location; applies only when the event has a venue map
 * @param timeZone    the zone the show dates are read in (ADR-008); the existing show dates are read in
 *                    the new zone
 * @param showDates   the new schedule, which replaces the old one; {@code null} leaves the schedule
 *                    alone, and an empty list is refused
 */
public record EventUpdateDTO(
    String eventId,
    String name,
    String description,
    String category,
    LocationDTO location,
    ZoneId timeZone,
    List<ShowDateDTO> showDates
) {}
