package com.ticketing.system.shared.dto;

import java.time.LocalDateTime;
import java.util.List;

// An error envelope: a code, a message, a correlation id and per-field errors. Nothing builds or
// returns it today; presenters turn a failure into text with ServiceErrors.
// 'fieldErrors' is populated for validation failures only.
public record ErrorResponseDTO(
    String errorCode,                    // domain exception class simple name
    String message,                      // human-readable
    LocalDateTime occurredAt,
    String correlationId,                // log-trace id for support
    List<ValidationErrorDTO> fieldErrors // empty if not a validation failure
) {}
