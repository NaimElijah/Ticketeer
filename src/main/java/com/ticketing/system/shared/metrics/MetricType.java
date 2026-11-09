package com.ticketing.system.shared.metrics;

/**
 * The platform-activity events tracked for the System Analytics dashboard
 * (UC-46). Purchases are intentionally absent — they are derived from the
 * immutable {@code OrderReceipt} history rather than recorded as live counters.
 *
 * @see ISystemMetrics
 */
public enum MetricType {
    /** A visitor starts a guest session. */
    VISITOR_ENTRY,
    /** A guest session ends. Expiry is the only path in use today. */
    VISITOR_EXIT,
    /** A member registers an account. */
    REGISTRATION,
    /** A member or guest reserves tickets. */
    RESERVATION
}
