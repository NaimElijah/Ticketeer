package com.ticketing.system.shared.dto;

/**
 * One zone of an event's venue layout, as the owner's editor reads it (see {@link VenueLayoutDTO}).
 *
 * @param rows        the number of seat rows of a seated zone, grouped by the leading letters of the
 *                    seat labels; {@code 0} for a standing zone
 * @param seatsPerRow the largest number of seats in one row of a seated zone; {@code 0} for a standing
 *                    zone
 * @param capacity    how many tickets a standing zone holds; {@code 0} for a seated zone
 * @param placement   where the zone sits on the venue grid; {@code null} when it is unplaced
 */
public record ZoneDetailDTO(
    String name,
    boolean seated,
    int rows,
    int seatsPerRow,
    int capacity,
    double price,
    GridPlacementDTO placement
) {}