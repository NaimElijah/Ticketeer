package com.ticketing.system.shared.event;

/**
 * Integration event published by the sales context (ReservationService) when a member
 * successfully reserves tickets. The notifications context listens for it and raises a
 * reservation-success notification. Mirrors the former
 * {@code INotificationService.notifyTicketReservationSuccess} call.
 */
public record TicketReservationSucceededNotice(
        int userId,    // the member who reserved the tickets
        int eventId,   // the event the reservation belongs to
        int zoneId,    // the zone within the event that was reserved
        int quantity   // how many tickets were reserved
) {
}
