package com.ticketing.system.shared.event;

/**
 * Integration event published by the sales context (ReservationService) when a member's
 * ticket-reservation attempt fails. The notifications context listens for it and raises a
 * reservation-failure notification. Mirrors the former
 * {@code INotificationService.notifyTicketReservationFailure} call.
 */
public record TicketReservationFailedNotice(
        int userId,    // the member whose reservation failed
        int eventId,   // the event the reservation attempt targeted
        int zoneId,    // the zone within the event that was attempted
        String reason  // human-readable failure reason to surface to the member
) {
}
