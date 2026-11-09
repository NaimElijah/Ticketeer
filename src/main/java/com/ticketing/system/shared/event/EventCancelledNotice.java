package com.ticketing.system.shared.event;

/**
 * Integration event published by the sales context (EventCancellationRefundService) once per
 * member who holds a ticket of a cancelled event, after the refund of one of that member's receipts
 * is recorded. A guest buyer has no user id and gets none. The notifications context listens for it
 * and raises an event-cancelled notification. The listener hands its components to
 * {@code INotificationService.notifyEventCancelled}.
 *
 * @param userId the ticket holder to notify
 * @param eventId the event that was cancelled
 * @param eventName the display name of the cancelled event
 */
public record EventCancelledNotice(
        int userId,
        int eventId,
        String eventName
) {
}
