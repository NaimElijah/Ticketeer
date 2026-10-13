package com.ticketing.system.catalog.application.port.out;

/**
 * Outbound port letting catalog ask whether an event has any sales history without importing the
 * sales context. This inverts the dependency (Dependency Inversion): the interface lives in catalog,
 * a sales adapter implements it, so the only source-code edge is {@code sales -> catalog} (the kept,
 * acyclic direction). Backs the {@code deleteEvent} guard (UC-19) that refuses to permanently delete
 * an event whose OrderReceipt/Ticket records would be orphaned.
 */
public interface EventSalesHistoryPort {

    /**
     * Reports whether any sale references the given event.
     *
     * @param eventId the event to check
     * @return {@code true} if at least one OrderReceipt references the event (i.e., it has sales history)
     */
    boolean hasSalesHistory(int eventId);
}
