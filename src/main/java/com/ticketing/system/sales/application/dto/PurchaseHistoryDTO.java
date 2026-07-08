package com.ticketing.system.sales.application.dto;

import com.ticketing.system.sales.domain.TicketStatus;

import java.time.LocalDateTime;
import java.util.List;

// Output of MemberAccountService.viewMyHistory() (UC-16).
// Shape is reusable for UC-22 (company sales) and UC-31 (admin global) views.
// Immutable per II.3.5.2 — fields reflect the data at time of purchase, not current.
public record PurchaseHistoryDTO(List<PurchaseRecordDTO> records) {

    // PurchaseRecordDTO is a read-model snapshot of an OrderReceipt.
    // It is not the OrderReceipt domain object itself.
    public record PurchaseRecordDTO(
        int orderReceiptId,
        Integer buyerUserId,       // null for guest purchases
        String guestEmail,         // null for member purchases
        LocalDateTime purchasedAt,
        double totalPaid,
        boolean refunded,
        List<TransactionRecordDTO> transactions,
        List<TicketRecordDTO> tickets,
        String buyerName           // resolved member username; null for guests (use guestEmail)
    ) {}
    
    public record TransactionRecordDTO(
        String type,
        String providerName,
        String externalTransactionId,
        double amount,
        String currency,
        LocalDateTime timestamp
    ) {}

    public record TicketRecordDTO(
        int ticketId,
        int zoneId,
        int eventId,
        int orderReceiptId,
        String seatNumber,     // nullable for standing zones
        double pricePaid,
        TicketStatus currentStatus,
        String eventName,      // resolved at mapping time; null if unresolved
        String zoneName,       // resolved at mapping time; null if unresolved
        String companyName,    // resolved at mapping time; null if unresolved
        String category,       // event category; null if unresolved
        LocalDateTime eventStartsAt, // first show date; null if unresolved
        String venue,          // event location; null if unresolved
        String barcode         // issued-ticket barcode; null if not yet issued
    ) {}
}
