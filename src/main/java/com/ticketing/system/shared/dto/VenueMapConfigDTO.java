package com.ticketing.system.shared.dto;

import java.util.List;

/**
 * Input to {@code EventManagementService.configureVenueMap} (UC-20): the event's venue map is replaced
 * by these zones and seats, and a DRAFT event with at least one zone becomes SCHEDULED. No Ticket is
 * created here; tickets are created at checkout.
 *
 * @param eventId   the event's id, as text
 * @param venueName not read by the service: the new map keeps the event's stored location
 * @param gridRows  the rows of the venue canvas grid; zero or less falls back to the default
 * @param gridCols  the columns of the venue canvas grid; zero or less falls back to the default
 * @param zones     the zones; zone ids are assigned 1 to N in this order
 */
public record VenueMapConfigDTO(
    String eventId,
    String venueName,
    int gridRows,
    int gridCols,
    List<ZoneConfigDTO> zones
) {
    /**
     * One zone definition: either seated (with a seat list) or standing (with a capacity and no seats).
     *
     * @param capacity  how many tickets a standing zone holds, which must be positive; not used for a
     *                  seated zone
     * @param seats     the seats of a seated zone; {@code null} for a standing zone
     * @param placement where the zone sits on the grid; {@code null} leaves the zone unplaced
     */
    public record ZoneConfigDTO(
        String zoneName,
        boolean seated,
        Integer capacity,
        List<SeatConfigDTO> seats,
        double pricePerTicket,
        GridPlacementDTO placement
    ) {}

    /**
     * One seat of a seated zone.
     */
    public record SeatConfigDTO(
            String label,
            double x,
            double y
    ) {}
}
