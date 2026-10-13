package com.ticketing.system.catalog.domain;

/**
 * Lifecycle of a single {@link Seat} inside a {@link SeatedZone}.
 *
 * <pre>
 *  AVAILABLE → RESERVED → SOLD
 *      ↑________|        |
 *      |_________________|   (release / refund flips back to AVAILABLE)
 * </pre>
 */
public enum SeatStatus {
    AVAILABLE,
    RESERVED,
    SOLD
}
