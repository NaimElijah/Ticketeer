package com.ticketing.system.shared.dto;

/**
 * One ticket's barcode as the issuer returned it (UC-34). Checkout stores only {@code barcodeValue},
 * on the ticket; nothing reads {@code format}. It carries no ticket id: ticket identity is minted by
 * {@code TicketRepository.nextId}, never by the issuer (#19).
 */
public record BarcodeDTO(
    String barcodeValue,                 // QR / Code-128 payload
    String format                        // "QR" / "CODE128" / etc.
) {}
