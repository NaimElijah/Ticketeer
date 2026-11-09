package com.ticketing.system.shared.dto;

/**
 * A seat of a seated zone, as the venue map shows it.
 *
 * @param label  the seat's label within its zone, such as {@code A12}
 * @param x      the seat's horizontal coordinate
 * @param y      the seat's vertical coordinate
 * @param status {@code AVAILABLE}, {@code RESERVED} or {@code SOLD}
 */
public record SeatDTO(
        String label,
        double x,
        double y,
        String status
) {}