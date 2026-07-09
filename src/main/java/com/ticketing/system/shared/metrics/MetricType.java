package com.ticketing.system.shared.metrics;

/**
 * The platform-activity events tracked for the System Analytics dashboard
 * (UC-46). Purchases are intentionally absent — they are derived from the
 * immutable {@code OrderReceipt} history rather than recorded as live counters.
 *
 * @see ISystemMetrics
 */
public enum MetricType {
    VISITOR_ENTRY,
    VISITOR_EXIT,
    REGISTRATION,
    RESERVATION
}
