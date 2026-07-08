package com.ticketing.system.shared.event;

/**
 * Integration event published by the sales context (ReservationService) when a member's
 * attempt to remove a ticket reservation fails. The notifications context listens for it and
 * raises a removal-failure notification. Mirrors the former
 * {@code INotificationService.notifyRemoveTicketReservationFailure} call.
 */
public record ReservationRemovalFailedNotice(
        int userId,    // the member whose removal attempt failed
        int eventId,   // the event the removal attempt targeted
        int zoneId,    // the zone within the event that was attempted
        String reason  // human-readable failure reason to surface to the member
) {
}
