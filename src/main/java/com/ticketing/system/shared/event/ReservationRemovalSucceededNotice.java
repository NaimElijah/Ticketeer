package com.ticketing.system.shared.event;

/**
 * Integration event published by the sales context (ReservationService) when a member
 * successfully removes a ticket reservation from their cart. The notifications context listens
 * for it and raises a removal-success notification. The listener hands its components to
 * {@code INotificationService.notifyRemoveTicketReservationSuccess}.
 *
 * @param userId the member who removed the reservation
 * @param eventId the event the removed reservation belonged to
 * @param zoneId the zone within the event that was removed
 * @param quantity how many tickets were removed
 */
public record ReservationRemovalSucceededNotice(
        int userId,
        int eventId,
        int zoneId,
        int quantity
) {
}
