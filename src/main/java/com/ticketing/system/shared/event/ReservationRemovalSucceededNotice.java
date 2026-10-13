package com.ticketing.system.shared.event;

/**
 * Integration event published by the sales context (ReservationService) when a member
 * successfully removes a ticket reservation from their cart. The notifications context listens
 * for it and raises a removal-success notification. Mirrors the former
 * {@code INotificationService.notifyRemoveTicketReservationSuccess} call.
 */
public record ReservationRemovalSucceededNotice(
        int userId,    // the member who removed the reservation
        int eventId,   // the event the removed reservation belonged to
        int zoneId,    // the zone within the event that was removed
        int quantity   // how many tickets were removed
) {
}
