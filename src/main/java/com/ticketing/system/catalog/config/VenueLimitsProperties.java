package com.ticketing.system.catalog.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Positive;

/**
 * The largest venue map a producer may configure (UC-20, OQ-27), bound from
 * {@code catalog.venue-limits.*} and validated at startup (ADR-003). {@code EventManagementService}
 * enforces them on every write that sizes a venue, because the venue editor's field limits are
 * advisory and a client can send any size.
 *
 * @param maxGridSize         the most rows, and the most columns, of a venue grid
 * @param maxSeatsPerZone     the most seats one seated zone may hold
 * @param maxStandingCapacity the most places one standing zone may hold
 * @param maxSeatsPerMap      the most seats across every seated zone of one map; standing places do
 *                            not count toward it
 */
@Validated
@ConfigurationProperties(prefix = "catalog.venue-limits")
public record VenueLimitsProperties(
        @Positive int maxGridSize,
        @Positive int maxSeatsPerZone,
        @Positive int maxStandingCapacity,
        @Positive int maxSeatsPerMap) {
}
