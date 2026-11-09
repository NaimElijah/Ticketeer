package com.ticketing.system.shared.dto;

import java.util.List;

// A reservation request as one value (UC-5 / UC-9). No service takes it today: ReservationService's
// reserveForMember / reserveForGuest take the ids and a catalog InventorySelectionDTO.
// Two selection modes (II.2.5):
//   - Specific seat:   seatNumbers populated
//   - Quantity-zone:   zoneId + quantity populated
public record ReservationRequestDTO(
        int eventId,
        int zoneId,
        Integer quantity,           // standing-zone reservation
        List<String> seatNumbers    // seated-zone reservation
) {}
