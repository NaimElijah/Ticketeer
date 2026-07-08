package com.ticketing.system.Core.Application.dto;
import com.ticketing.system.sales.application.service.ReservationService;

import java.util.List;

// Input to ReservationService.reserve() (UC-5 / UC-9).
// Two selection modes (II.2.5):
//   - Specific seat:   seatNumbers populated, quantity ignored
//   - Quantity-zone:   zoneId + quantity populated, seatNumbers null
public record ReservationRequestDTO(
        int eventId,
        int zoneId,
        Integer quantity,           // standing-zone reservation
        List<String> seatNumbers    // seated-zone reservation
) {}
