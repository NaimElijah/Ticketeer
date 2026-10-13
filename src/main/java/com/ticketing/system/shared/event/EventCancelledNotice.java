package com.ticketing.system.shared.event;

/**
 * Integration event published by the catalog context (EventManagementService) once per ticket
 * holder when an event is cancelled and refunds are processed. The notifications context listens
 * for it and raises an event-cancelled notification. Mirrors the former
 * {@code INotificationService.notifyEventCancelled} call.
 */
public record EventCancelledNotice(
        int userId,        // the ticket holder to notify
        int eventId,       // the event that was cancelled
        String eventName   // the display name of the cancelled event
) {
}
