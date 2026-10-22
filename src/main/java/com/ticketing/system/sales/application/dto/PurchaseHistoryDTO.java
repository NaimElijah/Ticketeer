package com.ticketing.system.sales.application.dto;

import com.ticketing.system.sales.domain.TicketStatus;

import java.time.LocalDateTime;
import java.time.ZonedDateTime;
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
        RefundStatus refundStatus, // over this record's lines only: a company's record ignores other companies' refunds
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
        ZonedDateTime eventStartsAt, // first show date, at the venue in its zone; null if unresolved
        String venue,          // event location; null if unresolved
        String barcode,        // issued-ticket barcode; null if not yet issued
        boolean refunded       // this line's refund is recorded (#21)
    ) {}

    /** How much of a record's lines has been refunded (#21). */
    public enum RefundStatus {
        /** No line is refunded. */
        NONE,
        /** Some lines are refunded, for example by one cancelled event of a multi-event order. */
        PARTIAL,
        /** Every line is refunded. */
        FULL;

        /** The status of a record made of {@code tickets}; a record with no lines is {@link #NONE}. */
        public static RefundStatus of(List<TicketRecordDTO> tickets) {
            long refunded = tickets.stream().filter(TicketRecordDTO::refunded).count();
            if (refunded == 0) {
                return NONE;
            }
            return refunded == tickets.size() ? FULL : PARTIAL;
        }
    }
}
