package com.ticketing.system.shared.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * An error envelope: a code, a message, a correlation id and per-field errors. Nothing builds or
 * returns it today; presenters turn a failure into text with {@code ServiceErrors}.
 *
 * @param errorCode     the simple name of the exception class
 * @param message       text a person can read
 * @param correlationId the log-trace id, for support
 * @param fieldErrors   the failed fields of a validation failure; empty for any other failure
 */
public record ErrorResponseDTO(
    String errorCode,
    String message,
    LocalDateTime occurredAt,
    String correlationId,
    List<ValidationErrorDTO> fieldErrors
) {}
