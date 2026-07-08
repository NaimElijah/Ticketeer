package com.ticketing.system.Core.Application.dto;
import com.ticketing.system.catalog.application.service.EventManagementService;

import java.util.List;

// Input to EventManagementService.configureVenueMap() (UC-20).
// On commit, Tickets are pre-generated per the unified-Ticket model.
public record VenueMapConfigDTO(
    String eventId,
    String venueName,
    int gridRows,        // venue canvas grid; <= 0 falls back to the VenueMap default
    int gridCols,
    List<ZoneConfigDTO> zones
) {
    // One zone definition. Either seated (with seat layout/list) or standing (only with capacity, null for seats).
    public record ZoneConfigDTO(
        String zoneName,
        boolean seated,
        Integer capacity,
        List<SeatConfigDTO> seats,  // null for standing zones
        double pricePerTicket,
        GridPlacementDTO placement  // null = leave the zone unplaced on the grid
    ) {}

    public record SeatConfigDTO(
            String label,
            double x,
            double y
    ) {}
}
