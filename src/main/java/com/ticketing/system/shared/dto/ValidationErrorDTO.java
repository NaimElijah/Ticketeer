package com.ticketing.system.shared.dto;

/**
 * One field-level validation failure, an element of {@link ErrorResponseDTO}'s {@code fieldErrors}.
 * Nothing builds it today.
 */
public record ValidationErrorDTO(
    String field,
    String rejectedValue,
    String message
) {}
