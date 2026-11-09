package com.ticketing.system.shared.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Output of {@code ReservationService.reserveForMember} and {@code reserveForGuest} and of
 * {@code removeForMember} and {@code removeForGuest} (UC-5/9): the selection that was held or
 * released, not the cart. {@code reservationExpiresAt} is the time a hold started by that call runs
 * out; a removal reports the same calculation from the time of the removal.
 */
public class ReservationResultDTO {

    private final int eventId;
    private final int zoneId;
    private final int quantity;
    private final List<String> seatNumbers;    // empty for standing reservations, populated for seated reservations
    private final LocalDateTime reservationExpiresAt;

    /**
     * Creates a result.
     *
     * @param quantity    how many tickets were held or released
     * @param seatNumbers the seats' labels; empty for a standing zone, and a {@code null} list is stored
     *                    as empty
     */
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
