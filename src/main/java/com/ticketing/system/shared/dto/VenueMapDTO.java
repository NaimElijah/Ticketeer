package com.ticketing.system.shared.dto;

import java.util.List;

// Output of CatalogService.getEventVenueMap() (UC-8).
// gridRows/gridCols describe the venue canvas; each zone's GridPlacementDTO
// positions it within that grid (null placement = auto-layout).
public record VenueMapDTO(
    int venueMapId,
    LocationDTO location,
    int gridRows,
    int gridCols,
    List<InventoryZoneDTO> inventoryZones
) {}