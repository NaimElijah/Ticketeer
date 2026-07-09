package com.ticketing.system.shared.dto;

import java.time.LocalDateTime;
import java.util.List;

// Output of TicketIssuer.issue() (UC-34).
public record IssuanceResultDTO(
    String issuanceTransactionId,
    String issuerName,
    LocalDateTime issuedAt,
    List<BarcodeDTO> barcodes
) {}
