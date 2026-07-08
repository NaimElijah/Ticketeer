package com.ticketing.system.Core.Application.dto;
import com.ticketing.system.sales.application.service.ReservationService;

import java.time.LocalDateTime;
import java.util.List;

// Output of ReservationService.reserve() (UC-5/9).
// Returns the lock outcome plus the resulting cart state so the UI doesn't need a second roundtrip.
public class ReservationResultDTO {

    private final int eventId;
    private final int zoneId;
    private final int quantity;
    private final List<String> seatNumbers;    // empty for standing reservations, populated for seated reservations
    private final LocalDateTime reservationExpiresAt;

    public ReservationResultDTO(int eventId, int zoneId, int quantity, List<String> seatNumbers, LocalDateTime reservationExpiresAt) {
        this.eventId = eventId;
        this.zoneId = zoneId;
        this.quantity = quantity;
        this.seatNumbers = seatNumbers == null ? List.of() : List.copyOf(seatNumbers);
        this.reservationExpiresAt = reservationExpiresAt;
    }

    public List<String> getSeatNumbers() {
        return seatNumbers;
    }

    public int getEventId() {
        return eventId;
    }

    public int getZoneId() {
        return zoneId;
    }

    public int getQuantity() {
        return quantity;
    }

    public LocalDateTime getReservationExpiresAt() {
        return reservationExpiresAt;
    }
}
