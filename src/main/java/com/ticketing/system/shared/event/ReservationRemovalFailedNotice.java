package com.ticketing.system.shared.event;

/**
 * Integration event published by the sales context (ReservationService) when a member's
 * attempt to remove a ticket reservation fails. The notifications context listens for it and
 * would raise a removal-failure notification, but none is delivered today: the notice is published inside
 * the removal's transaction, which rolls back on failure, so the after-commit listener never runs
 * (ADR-004). The listener hands its components to
 * {@code INotificationService.notifyRemoveTicketReservationFailure}.
 *
 * @param userId the member whose removal attempt failed
 * @param eventId the event the removal attempt targeted
 * @param zoneId the zone within the event that was attempted
 * @param reason human-readable failure reason to surface to the member
 */
public record ReservationRemovalFailedNotice(
        int userId,
        int eventId,
        int zoneId,
        String reason
) {
}
