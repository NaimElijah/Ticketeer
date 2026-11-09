package com.ticketing.system.shared.dto;

import java.util.List;

/**
 * The owner-side view of an event's venue layout: the canvas grid's size and the zones with their grid
 * placement. Returned by {@code EventManagementService.getEventZones} so the editor can restore the
 * saved grid size and zone positions when it reopens.
 *
 * @param gridRows the number of rows of the canvas grid
 * @param gridCols the number of columns of the canvas grid
 */
public record VenueLayoutDTO(
    int gridRows,
    int gridCols,
    List<ZoneDetailDTO> zones
) {}
