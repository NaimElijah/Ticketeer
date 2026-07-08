package com.ticketing.system.Core.Application.dto;
import com.ticketing.system.catalog.application.service.EventManagementService;

import java.util.List;

// Owner-side read of an event's venue layout: the canvas grid dimensions plus the
// zones (with their grid placement). Returned by EventManagementService.getEventZones
// so the editor can restore the saved grid size and zone positions on reopen.
public record VenueLayoutDTO(
    int gridRows,
    int gridCols,
    List<ZoneDetailDTO> zones
) {}
