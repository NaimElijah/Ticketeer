package com.ticketing.system.sales.domain;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.ticketing.system.shared.InvariantChecked;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

// V3: mapped to JPA. receiptId is an ASSIGNED @Id (minted by nextId(), never @GeneratedValue);
// userid is a nullable by-id column (member receipts) and guestEmail/guestSessionId identify guest
// receipts — the member/guest XOR stays enforced in checkInvariants, not at the DB. @Version drives
// optimistic locking. receiptLines and transactionRecords are owned value lists, each an
// @ElementCollection of @Embeddable in its own side-table, loaded eagerly so a detached receipt is
// fully usable. A protected no-arg ctor lets Hibernate hydrate; the factories still enforce the
// invariants.
@Entity
@Table(name = "receipts")
public class OrderReceipt implements InvariantChecked {

    @Id
    private int receiptId;

    // Member receipt identity
    @Column(name = "user_id")
    private Integer userid;                // nullable for guest receipts, positive integer for member receipts

    // Guest receipt identity
    @Column(name = "guest_email")
    private String guestEmail;             // nullable for member receipts, non-null/non-blank for guest receipts
    @Column(name = "guest_session_id")
    private String guestSessionId;         // nullable for member receipts, non-null/non-blank for guest receipts

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "receipt_lines", joinColumns = @JoinColumn(name = "receipt_id"))
    private List<ReceiptLine> receiptLines;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "receipt_transactions", joinColumns = @JoinColumn(name = "receipt_id"))
    private List<TransactionRecord> transactionRecords;

    @Column(name = "total_price", nullable = false)
    private double totalPrice;

    @Column(name = "purchase_time", nullable = false)
    private LocalDateTime purchaseTime;

    @Column(name = "is_refunded", nullable = false)
    private boolean isRefunded = false;

    @Version
    private Long version;

    /** For JPA only — do not call from application code. */
    protected OrderReceipt() { }

    private OrderReceipt(int receiptId, Integer userid, String guestEmail, String guestSessionId, double totalPrice,
            List<ReceiptLine> receiptLines, List<TransactionRecord> transactionRecords) {
        if (receiptId <= 0) {
            throw new IllegalArgumentException("receiptId must be positive");
        }

        if (totalPrice < 0) {
            throw new IllegalArgumentException("totalPrice must be non-negative");
        }

        if (receiptLines == null) {
            throw new IllegalArgumentException("receiptLines must not be null");
        }

        if (receiptLines.isEmpty()) {
            throw new IllegalArgumentException("receiptLines must not be empty");
        }

        boolean memberIdentity = userid != null;
        boolean guestIdentity = guestEmail != null && !guestEmail.isBlank()
                && guestSessionId != null && !guestSessionId.isBlank();

        if (memberIdentity == guestIdentity) {
            throw new IllegalArgumentException("OrderReceipt must belong to exactly one buyer type: member OR guest");
        }

        if (memberIdentity && userid <= 0) {
            throw new IllegalArgumentException("userid must be positive");
        }

        this.receiptId = receiptId;
        this.userid = userid;
        this.guestEmail = guestEmail;
        this.guestSessionId = guestSessionId;
        this.totalPrice = totalPrice;
        // Mutable defensive copy — Hibernate manages the @ElementCollection; the getter still
        // returns an immutable view, so callers cannot mutate the internal list.
        this.receiptLines = new ArrayList<>(receiptLines);
        this.transactionRecords = new ArrayList<>();
        this.purchaseTime = LocalDateTime.now();

        if (transactionRecords != null) {
            transactionRecords.forEach(this::addTransaction);
        }

        checkInvariants();
    }

    public static OrderReceipt forMember(int receiptId, int userId, double totalAmount, List<ReceiptLine> receiptLines) {
        return forMember(receiptId, userId, totalAmount, receiptLines, List.of());
    }

    public static OrderReceipt forMember(
            int receiptId,
            int userId,
            double totalAmount,
            List<ReceiptLine> receiptLines,
            List<TransactionRecord> transactionRecords
    ) {
        return new OrderReceipt(
                receiptId,
                userId,
                null,
                null,
                totalAmount,
                receiptLines,
                transactionRecords
        );
    }

    public static OrderReceipt forGuest(String guestEmail, String guestSessionId, int receiptId, double totalAmount, List<ReceiptLine> receiptLines) {
        return forGuest(guestEmail, guestSessionId, receiptId, totalAmount, receiptLines, List.of());
    }

    public static OrderReceipt forGuest(String guestEmail, String guestSessionId, int receiptId,
            double totalAmount, List<ReceiptLine> receiptLines, List<TransactionRecord> transactionRecords) {
        return new OrderReceipt(
                receiptId,
                null,
                guestEmail,
                guestSessionId,
                totalAmount,
                receiptLines,
                transactionRecords
        );
    }

    public int getId() {
        return receiptId;
    }

    // Keep this name because existing tests/code use it.
    public Integer getUserid() {
        return userid;
    }

    public Integer getHolderUserId() {
        return userid;
    }

    public String getGuestEmail() {
        return guestEmail;
    }

    public String getGuestSessionId() {
        return guestSessionId;
    }

    public boolean isMemberReceipt() {
        return userid != null;
    }

    public boolean isGuestReceipt() {
        return guestEmail != null && guestSessionId != null;
    }

    public List<Integer> getEventIds() {
        return receiptLines.stream()
                .map(ReceiptLine::getEventId)
                .distinct()
                .toList();
    }

    public boolean containsEventId(int eventId) {
        return receiptLines.stream()
                .anyMatch(line -> line.getEventId() == eventId);
    }

    public LocalDateTime getPurchaseTime() {
        return purchaseTime;
    }

    public double getTotalAmount() {
        return totalPrice;
    }

    public List<ReceiptLine> getReceiptLines() {
        return List.copyOf(receiptLines);
    }

    public boolean wasRefunded() {
        return isRefunded;
    }

    public void markRefunded() {
        this.isRefunded = true;
        checkInvariants();
    }

    public List<TransactionRecord> getTransactionRecords() {
        return List.copyOf(transactionRecords);
    }



    // Helper methods to extract specific transaction info from the list of transaction records. These are not strictly necessary but can be convenient for common queries.

    // For simplicity we assume there is at most one payment charge per receipt. In a real system we might want to enforce this or handle multiple charges more robustly.
    public Optional<Integer> getPaymentTransactionId() {
        return transactionRecords.stream()
                .filter(record -> record.getType() == TransactionRecord.TransactionType.PAYMENT_CHARGE)
                .map(TransactionRecord::getExternalTransactionId)
                .map(Integer::parseInt)
                .findFirst();
    }

    // For simplicity, we assume all payment charges for a receipt use the same currency. In a real system we might want to enforce this or handle multiple currencies more robustly.
    public String getPaymentCurrency() {
        return transactionRecords.stream()
                .filter(record -> record.getType() == TransactionRecord.TransactionType.PAYMENT_CHARGE)
                .map(TransactionRecord::getCurrency)
                .filter(currency -> currency != null && !currency.isBlank())
                .findFirst()
                .orElse("ILS");
    }

    // For simplicity we assume there is at most one ticket issuance per receipt. In a real system we might want to enforce this or handle multiple issuances more robustly.
    public void markRefunded(TransactionRecord refundRecord) {
        addTransaction(refundRecord);
        this.isRefunded = true;
        checkInvariants();
    }





    public void addTransaction(TransactionRecord record) {
        Objects.requireNonNull(record, "transaction record must not be null");
        record.checkInvariants();
        transactionRecords.add(record);
        checkInvariants();
    }

    @Override
    public void checkInvariants() {
        if (receiptId <= 0) {
            throw new IllegalStateException("OrderReceipt invariant violated: receiptId must be positive");
        }

        if (totalPrice < 0) {
            throw new IllegalStateException("OrderReceipt invariant violated: totalPrice must be non-negative");
        }

        if (purchaseTime == null) {
            throw new IllegalStateException("OrderReceipt invariant violated: purchaseTime must not be null");
        }

        if (receiptLines == null) {
            throw new IllegalStateException("OrderReceipt invariant violated: receiptLines must not be null");
        }

        if (transactionRecords == null) {
            throw new IllegalStateException("OrderReceipt invariant violated: transactionRecords must not be null");
        }

        if (receiptLines.isEmpty()) {
            throw new IllegalStateException("OrderReceipt invariant violated: receiptLines must not be empty");
        }

        boolean isMember = userid != null;
        boolean isGuest = guestEmail != null && !guestEmail.isBlank() && guestSessionId != null && !guestSessionId.isBlank();

        if (isMember == isGuest) {
            throw new IllegalStateException("OrderReceipt invariant violated: must be exactly member OR guest");
        }

        if (isMember && userid <= 0) {
            throw new IllegalStateException("OrderReceipt invariant violated: userid must be positive");
        }

        for (ReceiptLine line : receiptLines) {
            Objects.requireNonNull(line, "OrderReceipt invariant violated: receipt line must not be null");
            line.checkInvariants();
        }

        for (TransactionRecord record : transactionRecords) {
            Objects.requireNonNull(record, "OrderReceipt invariant violated: transaction record must not be null");
            record.checkInvariants();
        }
    }
}
