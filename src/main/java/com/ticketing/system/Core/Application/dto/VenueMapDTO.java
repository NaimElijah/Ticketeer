package com.ticketing.system.Core.Application.dto;
import com.ticketing.system.catalog.application.service.CatalogService;

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