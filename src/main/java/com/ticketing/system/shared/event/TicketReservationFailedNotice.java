package com.ticketing.system.shared.event;

/**
 * Integration event published by the sales context (ReservationService) when a member's
 * ticket-reservation attempt fails. The notifications context listens for it and raises a
 * reservation-failure notification. The listener hands its components to
 * {@code INotificationService.notifyTicketReservationFailure}.
 *
 * @param userId the member whose reservation failed
 * @param eventId the event the reservation attempt targeted
 * @param zoneId the zone within the event that was attempted
 * @param reason human-readable failure reason to surface to the member
 */
public record TicketReservationFailedNotice(
        int userId,
        int eventId,
        int zoneId,
        String reason
) {
}
