package com.ticketing.system.shared.event;

/**
 * Integration event published by the sales context (ReservationService) when a member
 * successfully reserves tickets. The notifications context listens for it and raises a
 * reservation-success notification. The listener hands its components to
 * {@code INotificationService.notifyTicketReservationSuccess}.
 *
 * @param userId the member who reserved the tickets
 * @param eventId the event the reservation belongs to
 * @param zoneId the zone within the event that was reserved
 * @param quantity how many tickets were reserved
 */
public record TicketReservationSucceededNotice(
        int userId,
        int eventId,
        int zoneId,
        int quantity
) {
}
