package com.ticketing.system.shared.dto;

// A zone's placement on the venue grid: 1-based start cell + cell spans.
// Used both as editor input (VenueMapConfigDTO) and read output (InventoryZoneDTO /
// ZoneDetailDTO). A null GridPlacementDTO means the zone has no explicit placement
// and the preview should fall back to auto-layout.
public record GridPlacementDTO(
    int row,
    int col,
    int rowSpan,
    int colSpan
) {}
