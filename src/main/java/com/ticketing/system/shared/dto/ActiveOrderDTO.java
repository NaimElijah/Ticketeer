package com.ticketing.system.shared.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * A buyer's cart as the UI shows it: the held tickets, their total price and the time left to buy.
 *
 * <p>Returned by {@code ReservationService}'s {@code restoreActiveOrder},
 * {@code restoreActiveOrderForGuest}, {@code viewMyActiveOrder} and
 * {@code renewReservationsFor*Checkout}, and carried by {@link LoginDTO} when a login resumes a cart
 * (UC-13). Those methods fill in each line's event name.
 *
 * @param userId                       the member who owns the cart; {@code null} for a guest cart
 * @param sessionId                    the session the cart is attached to. Always set for a guest cart. A
 *                                     member cart carries it only while attached to a member session
 * @param remainingSecondsBeforeExpiry seconds left on the line whose hold runs out first, computed when
 *                                     the DTO is built so the UI can show a countdown; {@code 0} for an
 *                                     empty cart or once a hold has run out
 * @param currentTotalPrice            the sum of the lines' prices at the time each was reserved
 */
public record ActiveOrderDTO(
        Integer userId,
        String sessionId,
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

    /**
     * Leaves out the guest session id, which is the guest's credential, so a logged cart or an
     * exception message built from one does not carry it (SLR.2).
     */
    @Override
    public String toString() {
        return "ActiveOrderDTO[userId=" + userId
                + ", createdAt=" + createdAt
                + ", remainingSecondsBeforeExpiry=" + remainingSecondsBeforeExpiry
                + ", currentTotalPrice=" + currentTotalPrice
                + ", lines=" + lines + "]";
    }
}
