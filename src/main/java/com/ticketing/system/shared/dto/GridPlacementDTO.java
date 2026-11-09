package com.ticketing.system.shared.dto;

/**
 * A zone's placement on the venue grid: a 1-based start cell and cell spans. It is editor input in
 * {@link VenueMapConfigDTO} and read output in {@link InventoryZoneDTO} and {@link ZoneDetailDTO}. A
 * {@code null} placement means the zone has no explicit placement and the preview falls back to
 * auto-layout. A placement must stay inside the venue's grid and may not overlap another zone's.
 *
 * @param row     the 1-based row of the first cell
 * @param col     the 1-based column of the first cell
 * @param rowSpan the number of rows the zone covers, at least 1
 * @param colSpan the number of columns the zone covers, at least 1
 */
public record GridPlacementDTO(
    int row,
    int col,
    int rowSpan,
    int colSpan
) {}
