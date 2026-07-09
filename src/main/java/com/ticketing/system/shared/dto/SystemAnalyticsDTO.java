package com.ticketing.system.shared.dto;

/**
 * Read-side snapshot for the System Analytics dashboard (UC-46 / #43, #279).
 *
 * <p>Market status is carried separately by {@link MarketStateDTO}; this record
 * holds the platform performance metrics. Rates are per-minute over the trailing
 * {@code windowMinutes}; throughput figures are totals over the trailing hour;
 * {@code activeVisitors} is point-in-time and the {@code total*} fields are
 * cumulative since startup.
 */
public record SystemAnalyticsDTO(
    long activeVisitors,
    double visitorEntryRatePerMin,
    double visitorExitRatePerMin,
    double registrationRatePerMin,
    double reservationRatePerMin,
    double purchaseRatePerMin,
    long reservationThroughputHr,
    long purchaseThroughputHr,
    long totalVisitors,
    long totalRegistrations,
    long totalReservations,
    int windowMinutes
) {}
