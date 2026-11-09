package com.ticketing.system.shared.dto;

/**
 * One field-level validation failure, an element of {@link ErrorResponseDTO}'s {@code fieldErrors}.
 * Nothing builds it today.
 *
 * @param field         the name of the field that failed
 * @param rejectedValue the value that was rejected, as text
 * @param message       why it was rejected
 */
public record ValidationErrorDTO(
    String field,
    String rejectedValue,
    String message
) {}
