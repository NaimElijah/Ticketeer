package com.ticketing.system.catalog.application.port.in;

/**
 * Catalog inbound query port exposing read-only event display data to other contexts' read models.
 *
 * <p>Lets purchase-history mappers (e.g. sales' {@code OrderReceiptMapper}) resolve event/zone/venue
 * display fields through a sales-safe DTO instead of loading and reading a {@code catalog.domain.Event}
 * directly, keeping catalog the sole owner of its aggregate.
 */
public interface CatalogEventDisplayPort {

    /**
     * Returns the display projection for an event, or {@code null} if the event does not exist (or
     * cannot be loaded). Never throws — callers use it for best-effort read-model enrichment.
     *
     * @param eventId the event to describe
     * @return the sales-safe display info, or {@code null} when unavailable
     */
    EventDisplayInfoDTO describeEvent(int eventId);
}
