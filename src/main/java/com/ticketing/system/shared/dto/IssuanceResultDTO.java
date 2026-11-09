package com.ticketing.system.shared.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Output of {@code TicketIssuer.issue} (UC-34). Checkout refuses a result with a blank transaction id
 * or issuer name.
 *
 * @param issuanceTransactionId the issuer's id for the issuance
 * @param issuerName            which issuer answered
 * @param issuedAt              when the tickets were issued, in UTC wall time (ADR-008)
 * @param barcodes              one barcode per requested item, in the order requested
 */
public record IssuanceResultDTO(
    String issuanceTransactionId,
    String issuerName,
    LocalDateTime issuedAt,
    List<BarcodeDTO> barcodes
) {}
