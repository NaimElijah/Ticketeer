package com.ticketing.system.sales.application.dto;

import com.ticketing.system.sales.domain.TicketStatus;

import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * Read model of purchase history: the purchases of one member (UC-16, from
 * {@code MemberAccountService.viewMyHistory()}), and the same shape for a company's sales (UC-22) and the
 * admin's global history (UC-31). The record is immutable (II.3.5.2). Prices and the purchase time are
 * those recorded at purchase; transactions grow with later refunds, and a ticket's status and refund
 * state are current.
 *
 * @param records the purchases, one per receipt
 */
public record PurchaseHistoryDTO(List<PurchaseRecordDTO> records) {

    /**
     * A read-model snapshot of an {@code OrderReceipt}, not the domain object itself.
     *
     * @param orderReceiptId the receipt id
     * @param buyerUserId    the member's user id; null for guest purchases
     * @param guestEmail     the guest's email; null for member purchases
     * @param purchasedAt    when the purchase completed, as UTC wall time (ADR-008)
     * @param totalPaid      the total of the lines the record covers
     * @param refundStatus   how much of this record's lines is refunded: over this record's lines only, so
     *     a company's record ignores other companies' refunds
     * @param transactions   the order's payment, issuance and refund records; may be empty
     * @param tickets        one entry per line the record covers
     * @param buyerName      the member's username, resolved when the record is built; null for guests
     *     (use {@code guestEmail}) and when the member cannot be found
     */
    public record PurchaseRecordDTO(
        int orderReceiptId,
        Integer buyerUserId,
        String guestEmail,
        LocalDateTime purchasedAt,
        double totalPaid,
        RefundStatus refundStatus,
        List<TransactionRecordDTO> transactions,
        List<TicketRecordDTO> tickets,
        String buyerName
    ) {}

    /**
     * One external transaction of a receipt.
     *
     * @param type                  the name of the {@code TransactionRecord.TransactionType}
     * @param providerName          the gateway or issuer that made the transaction
     * @param externalTransactionId the provider's id for the transaction
     * @param amount                the amount; zero for a ticket issuance
     * @param currency              the currency; null for a ticket issuance
     * @param timestamp             when the provider made the transaction
     */
    public record TransactionRecordDTO(
        String type,
        String providerName,
        String externalTransactionId,
        double amount,
        String currency,
        LocalDateTime timestamp
    ) {}

    /**
     * One purchased place. The display fields are resolved when the record is built and are null when
     * the event or company they come from is missing, or no port was passed.
     *
     * @param seatNumber    the seat label; null for a standing zone
     * @param currentStatus the ticket's status now; a line with no stored ticket reads REFUNDED or PAID
     *     from its refund state
     * @param eventName     the event's name; null if unresolved
     * @param zoneName      the zone's name; null if unresolved
     * @param companyName   the producing company's name; null if unresolved
     * @param category      the event category; null if unresolved
     * @param eventStartsAt the first show date, at the venue in its zone; null if unresolved
     * @param venue         the event location; null if unresolved
     * @param barcode       the issued ticket's barcode; null if not issued
     * @param refunded      whether this line's refund is recorded (#21)
     */
    public record TicketRecordDTO(
        int ticketId,
        int zoneId,
        int eventId,
        int orderReceiptId,
        String seatNumber,
        double pricePaid,
        TicketStatus currentStatus,
        String eventName,
        String zoneName,
        String companyName,
        String category,
        ZonedDateTime eventStartsAt,
        String venue,
        String barcode,
        boolean refunded
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
