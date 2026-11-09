package com.ticketing.system.shared.event;

/**
 * Integration event published by the sales context (ReservationService) when a member's
 * ticket-reservation attempt fails. The notifications context listens for it and would raise a
 * reservation-failure notification, but none is delivered today: the notice is published inside the
 * reservation's transaction, which rolls back on failure, so the after-commit listener never runs
 * (ADR-004). The listener hands its components to
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
