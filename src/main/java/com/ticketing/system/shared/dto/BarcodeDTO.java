package com.ticketing.system.shared.dto;

/**
 * One ticket's barcode as the issuer returned it (UC-34). Checkout stores only {@code barcodeValue},
 * on the ticket; nothing reads {@code format}. It carries no ticket id: ticket identity is minted by
 * {@code TicketRepository.nextId}, never by the issuer (#19).
 *
 * @param barcodeValue the payload the barcode encodes
 * @param format       the barcode type, such as {@code "QR"}; both issuers return {@code "QR"} today
 */
public record BarcodeDTO(
    String barcodeValue,
    String format
) {}
