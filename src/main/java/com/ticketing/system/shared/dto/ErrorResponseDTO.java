package com.ticketing.system.shared.dto;

import java.time.LocalDateTime;
import java.util.List;

// Generic error envelope returned by the application layer when an exception is caught.
// V2 (REST) will map this to an HTTP error body.
// 'fieldErrors' is populated for validation failures only.
public record ErrorResponseDTO(
    String errorCode,                    // domain exception class simple name
    String message,                      // human-readable
    LocalDateTime occurredAt,
    String correlationId,                // log-trace id for support
    List<ValidationErrorDTO> fieldErrors // empty if not a validation failure
) {}
