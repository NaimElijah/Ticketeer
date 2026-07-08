package com.ticketing.system.shared.dto;

import java.time.LocalDateTime;
import java.util.List;

// Cart view returned by ReservationService (UC-5/9), restoration (UC-13),
// and any cart-display query.
// 'remainingSecondsBeforeExpiry' is computed at read time so the UI can show countdown.
public record ActiveOrderDTO(
        Integer userId, // null for Guest active orders
        String sessionId, // null for Member active orders
        LocalDateTime createdAt,
        long remainingSecondsBeforeExpiry,
        double currentTotalPrice,
        List<CartLineDTO> lines) {
    public record CartLineDTO(
            int eventId,
            String eventName,
            int zoneId,
            String seatNumber,  // null for standing-zone tickets
            double pricePerTicket,
            LocalDateTime addedAt) {

       
    }


}
