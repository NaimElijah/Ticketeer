package com.ticketing.system.shared.dto;

import java.util.List;

/**
 * Output of {@code CatalogService.getEventVenueMap} (UC-8). {@code gridRows} and {@code gridCols}
 * describe the venue canvas; each zone's {@link GridPlacementDTO} positions it within that grid, and
 * a {@code null} placement means auto-layout.
 */
public record VenueMapDTO(
    int venueMapId,
    LocationDTO location,
    int gridRows,
    int gridCols,
    List<InventoryZoneDTO> inventoryZones
) {}