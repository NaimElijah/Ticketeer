package com.ticketing.system.catalog.domain;

// Lifecycle states for an Event (UC-4 / UC-19 / UC-32 design walkthrough).
// The transitions are:
//   DRAFT      → SCHEDULED  (UC-20: VenueMap bound, tickets pre-generated)
//   SCHEDULED  → ON_SALE    (UC-32: market opened or per-event publish)
//   ON_SALE    → SOLD_OUT   (no AVAILABLE tickets remain)
//   *          → CANCELED   (UC-4 trigger for refunds)
//   ON_SALE / SOLD_OUT → COMPLETED  (last show date passed)
public enum EventStatus {
    DRAFT,
    SCHEDULED,
    ON_SALE,
    SOLD_OUT,
    CANCELED,
    COMPLETED
}
