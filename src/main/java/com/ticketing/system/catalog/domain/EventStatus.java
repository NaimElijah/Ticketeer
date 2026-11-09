package com.ticketing.system.catalog.domain;

/**
 * Lifecycle states of an {@link Event} (UC-4 / UC-19 / UC-32). The transitions are:
 * <pre>
 *   DRAFT      → SCHEDULED  (UC-20: a VenueMap with at least one zone is bound)
 *   SCHEDULED  → ON_SALE    (UC-19: the event is published; opening the market does not do it)
 *   ON_SALE    → SOLD_OUT   (no AVAILABLE seat or standing place remains)
 *   SOLD_OUT   → ON_SALE    (a seat or place frees up)
 *   any but COMPLETED → CANCELED  (UC-4 trigger for refunds)
 *   ON_SALE / SOLD_OUT → COMPLETED  (last show date passed)
 * </pre>
 */
public enum EventStatus {
    DRAFT,
    SCHEDULED,
    ON_SALE,
    SOLD_OUT,
    CANCELED,
    COMPLETED
}
