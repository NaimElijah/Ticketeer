package com.ticketing.system.catalog.domain.event;

/**
 * Catalog domain event signalling that an event was soft-cancelled (UC-19). The sales context
 * listens for it (EventCancellationRefundListener) to run the refund / ticket-void / holder-
 * notification flow, so catalog no longer reaches into any sales type. Carrying the event name
 * lets the sales side build holder notifications without loading the catalog Event aggregate.
 * A plain record — no framework coupling, safe to live in the domain package.
 *
 * @param eventId   the id of the cancelled event
 * @param eventName the display name of the cancelled event (for notifications)
 */
public record EventCancelledEvent(int eventId, String eventName) {
}
