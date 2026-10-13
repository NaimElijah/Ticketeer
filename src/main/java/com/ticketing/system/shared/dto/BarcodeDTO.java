package com.ticketing.system.shared.dto;

// Per-ticket barcode returned by the issuer (UC-34).
// Stored locally on Ticket as part of the unified-Ticket defensive copy. It carries no ticket
// id: ticket identity is minted by TicketRepository.nextId, never by the issuer (#19).
public record BarcodeDTO(
    String barcodeValue,                 // QR / Code-128 payload
    String format                        // "QR" / "CODE128" / etc.
) {}
