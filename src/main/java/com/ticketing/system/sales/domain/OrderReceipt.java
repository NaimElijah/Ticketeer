package com.ticketing.system.sales.domain;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;

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

/**
 * The record of one purchase: the lines bought, the payment and issuance transactions behind them, and
 * the refund state of each line. A receipt belongs to exactly one buyer, a member (user id) or a guest
 * (email and session id); the factories {@code forMember} and {@code forGuest} create it.
 *
 * <p>Refund state is tracked per line ({@link LineRefundState}, ADR-001) so a cancelled event's refund
 * can be claimed, retried and recorded without touching the order's other lines (#20). The order-level
 * {@code is_refunded} column mirrors "every line refunded" (#21): it is re-synced on every line change,
 * and readers derive the answer from the lines, so a stale stored value is never trusted.
 *
 * <p>The receipt id is assigned by the application ({@code OrderReceiptRepository.nextId()}), and the
 * lines and transactions are owned lists loaded with the receipt.
 */
// V3: mapped to JPA. receiptId is an ASSIGNED @Id (never @GeneratedValue); userid is a nullable by-id
// column (member receipts) and guestEmail/guestSessionId identify guest receipts — the member/guest XOR
// stays enforced in checkInvariants, not at the DB. @Version drives optimistic locking. receiptLines and
// transactionRecords are each an @ElementCollection of @Embeddable in its own side-table, loaded eagerly so
// a detached receipt is fully usable. A protected no-arg ctor lets Hibernate hydrate; the factories still
// enforce the invariants.
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
            List<ReceiptLine> receiptLines, LocalDateTime purchaseTime, List<TransactionRecord> transactionRecords) {
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
        this.purchaseTime = purchaseTime;

        if (transactionRecords != null) {
            transactionRecords.forEach(this::addTransaction);
        }

        checkInvariants();
    }

    /**
     * Creates a member receipt with no transaction records.
     *
     * @param receiptId    the receipt id, positive
     * @param userId       the buyer's user id, positive
     * @param totalAmount  the total paid, not negative
     * @param receiptLines the purchased lines, not empty
     * @param purchaseTime when the purchase completed, as UTC wall time from the injected clock (ADR-008)
     * @throws IllegalArgumentException if an argument breaks the limits above
     */
    public static OrderReceipt forMember(int receiptId, int userId, double totalAmount, List<ReceiptLine> receiptLines,
            LocalDateTime purchaseTime) {
        return forMember(receiptId, userId, totalAmount, receiptLines, purchaseTime, List.of());
    }

    /**
     * Creates a member receipt.
     *
     * @param receiptId          the receipt id, positive
     * @param userId             the buyer's user id, positive
     * @param totalAmount        the total paid, not negative
     * @param receiptLines       the purchased lines, not empty
     * @param purchaseTime       when the purchase completed, as UTC wall time (ADR-008)
     * @param transactionRecords the payment and issuance records to keep on the receipt; may be
     *     {@code null} for none
     * @throws IllegalArgumentException if an argument breaks the limits above
     */
    public static OrderReceipt forMember(
            int receiptId,
            int userId,
            double totalAmount,
            List<ReceiptLine> receiptLines,
            LocalDateTime purchaseTime,
            List<TransactionRecord> transactionRecords
    ) {
        return new OrderReceipt(
                receiptId,
                userId,
                null,
                null,
                totalAmount,
                receiptLines,
                purchaseTime,
                transactionRecords
        );
    }

    /**
     * Creates a guest receipt with no transaction records.
     *
     * @param guestEmail     the buyer's email, not blank
     * @param guestSessionId the buyer's guest session id, not blank
     * @param receiptId      the receipt id, positive
     * @param totalAmount    the total paid, not negative
     * @param receiptLines   the purchased lines, not empty
     * @param purchaseTime   when the purchase completed, as UTC wall time (ADR-008)
     * @throws IllegalArgumentException if an argument breaks the limits above
     */
    public static OrderReceipt forGuest(String guestEmail, String guestSessionId, int receiptId, double totalAmount,
            List<ReceiptLine> receiptLines, LocalDateTime purchaseTime) {
        return forGuest(guestEmail, guestSessionId, receiptId, totalAmount, receiptLines, purchaseTime, List.of());
    }

    /**
     * Creates a guest receipt.
     *
     * @param guestEmail         the buyer's email, not blank
     * @param guestSessionId     the buyer's guest session id, not blank
     * @param receiptId          the receipt id, positive
     * @param totalAmount        the total paid, not negative
     * @param receiptLines       the purchased lines, not empty
     * @param purchaseTime       when the purchase completed, as UTC wall time (ADR-008)
     * @param transactionRecords the payment and issuance records to keep on the receipt; may be
     *     {@code null} for none
     * @throws IllegalArgumentException if an argument breaks the limits above
     */
    public static OrderReceipt forGuest(String guestEmail, String guestSessionId, int receiptId,
            double totalAmount, List<ReceiptLine> receiptLines, LocalDateTime purchaseTime,
            List<TransactionRecord> transactionRecords) {
        return new OrderReceipt(
                receiptId,
                null,
                guestEmail,
                guestSessionId,
                totalAmount,
                receiptLines,
                purchaseTime,
                transactionRecords
        );
    }

    public int getId() {
        return receiptId;
    }

    /** Returns the member's user id, or {@code null} for a guest receipt; the same as {@link #getHolderUserId}. */
    public Integer getUserid() {
        return userid;
    }

    /** Returns the member's user id, or {@code null} for a guest receipt. */
    public Integer getHolderUserId() {
        return userid;
    }

    /** Returns the guest's email, or {@code null} for a member receipt. */
    public String getGuestEmail() {
        return guestEmail;
    }

    /** Returns the guest session id the purchase was made under, or {@code null} for a member receipt. */
    public String getGuestSessionId() {
        return guestSessionId;
    }

    public boolean isMemberReceipt() {
        return userid != null;
    }

    public boolean isGuestReceipt() {
        return guestEmail != null && guestSessionId != null;
    }

    /** Returns the distinct ids of the events the receipt's lines are for. */
    public List<Integer> getEventIds() {
        return receiptLines.stream()
                .map(ReceiptLine::getEventId)
                .distinct()
                .toList();
    }

    /** Returns whether any line of the receipt is for the event. */
    public boolean containsEventId(int eventId) {
        return receiptLines.stream()
                .anyMatch(line -> line.getEventId() == eventId);
    }

    /** Returns when the purchase completed, as UTC wall time (ADR-008). */
    public LocalDateTime getPurchaseTime() {
        return purchaseTime;
    }

    /** Returns the total paid at purchase; refunds do not change it (see {@link #netAmount}). */
    public double getTotalAmount() {
        return totalPrice;
    }

    /**
     * Returns an unmodifiable copy of the lines. A receipt that was refunded before refund state was kept
     * per line reads every line as REFUNDED.
     */
    public List<ReceiptLine> getReceiptLines() {
        if (isRefundedWithoutLineState()) {
            return receiptLines.stream().map(line -> line.withRefundState(LineRefundState.REFUNDED)).toList();
        }
        return List.copyOf(receiptLines);
    }

    /**
     * Whether every line of this order has been refunded; a partly refunded order is not. Derived from
     * the lines rather than the stored flag: while #129 ran, a partial refund also set that flag.
     */
    public boolean isFullyRefunded() {
        return getReceiptLines().stream().allMatch(ReceiptLine::isRefunded);
    }

    /**
     * What this order still brings in: the prices of its lines that are not refunded. A line whose
     * refund is in flight (PENDING) still counts until the refund is recorded.
     */
    public double netAmount() {
        return getReceiptLines().stream()
                .filter(line -> !line.isRefunded())
                .mapToDouble(ReceiptLine::getPriceAtReservation)
                .sum();
    }

    /**
     * Refunds the whole order without a transaction record: every line becomes REFUNDED.
     *
     * @throws IllegalStateException if a line is already refunded or claimed by a refund in flight
     */
    public void markRefunded() {
        refundEveryLine();
        checkInvariants();
    }

    /**
     * Whether any refund has been recorded on this order or is in flight for one of its lines. A
     * whole-order refund is allowed only while this is {@code false}; otherwise it would charge back
     * money that another refund has already returned or is returning (#20).
     */
    public boolean hasRefundedOrPendingLines() {
        return isRefunded || receiptLines.stream().anyMatch(line -> line.getRefundState() != LineRefundState.NONE);
    }

    // A receipt refunded before refund state moved onto the lines (#20) records the refund only in the
    // order-level flag, and every line reads NONE. Its lines count as refunded, so an upgraded database
    // fails closed: at worst a refund is withheld, never paid twice.
    private boolean isRefundedWithoutLineState() {
        return isRefunded && receiptLines.stream().allMatch(line -> line.getRefundState() == LineRefundState.NONE);
    }

    /**
     * Claims this order's unrefunded lines for {@code eventId} ahead of a gateway refund (#20,
     * ADR-001). They move from NONE to PENDING, so a concurrent or repeated run finds nothing to claim
     * and never refunds them twice. The caller must persist the claim before calling the gateway.
     *
     * @param eventId the cancelled event
     * @return the amount paid for the claimed lines, or empty when no line for the event is unrefunded
     */
    public OptionalDouble claimRefundForEvent(int eventId) {
        if (isRefundedWithoutLineState()) {
            return OptionalDouble.empty();
        }
        double claimedAmount = 0.0;
        boolean claimedAny = false;
        for (int i = 0; i < receiptLines.size(); i++) {
            ReceiptLine line = receiptLines.get(i);
            if (line.getEventId() == eventId && line.getRefundState() == LineRefundState.NONE) {
                receiptLines.set(i, line.withRefundState(LineRefundState.PENDING));
                claimedAmount += line.getPriceAtReservation();
                claimedAny = true;
            }
        }
        syncRefundedFlag();
        checkInvariants();
        return claimedAny ? OptionalDouble.of(claimedAmount) : OptionalDouble.empty();
    }

    /** Whether a refund for {@code eventId} has been claimed on this order and not yet resolved. */
    public boolean hasPendingRefundFor(int eventId) {
        return receiptLines.stream()
                .anyMatch(line -> line.getEventId() == eventId && line.getRefundState() == LineRefundState.PENDING);
    }

    /**
     * Releases a claim whose gateway refund failed: the event's PENDING lines return to NONE, so a
     * later run can claim them again.
     *
     * @throws IllegalStateException if this order holds no claim for the event
     */
    public void releaseRefundClaim(int eventId) {
        moveClaimedLines(eventId, LineRefundState.NONE);
        syncRefundedFlag();
        checkInvariants();
    }

    /**
     * Records the gateway refund for the event's claimed lines: they become REFUNDED and
     * {@code refundRecord} is added to the order's transactions.
     *
     * @throws IllegalArgumentException if {@code refundRecord} is not a REFUND record
     * @throws IllegalStateException    if this order holds no claim for the event
     */
    public void completeEventRefund(int eventId, TransactionRecord refundRecord) {
        Objects.requireNonNull(refundRecord, "refund record must not be null");
        if (refundRecord.getType() != TransactionRecord.TransactionType.REFUND) {
            throw new IllegalArgumentException("expected a REFUND record, got " + refundRecord.getType());
        }
        moveClaimedLines(eventId, LineRefundState.REFUNDED);
        syncRefundedFlag();
        addTransaction(refundRecord);
    }

    /**
     * Completes a claim whose lines cost nothing, so no money moved and there is no refund record.
     *
     * @throws IllegalStateException if this order holds no claim for the event
     */
    public void completeUnchargedEventRefund(int eventId) {
        moveClaimedLines(eventId, LineRefundState.REFUNDED);
        syncRefundedFlag();
        checkInvariants();
    }

    private void syncRefundedFlag() {
        this.isRefunded = isFullyRefunded();
    }

    /** Returns an unmodifiable copy of the receipt's payment, issuance and refund records. */
    public List<TransactionRecord> getTransactionRecords() {
        return List.copyOf(transactionRecords);
    }



    /**
     * Returns the gateway's transaction id of the receipt's payment charge, or empty if it has none.
     * Assumes at most one payment charge per receipt and returns the first.
     */
    public Optional<Integer> getPaymentTransactionId() {
        return transactionRecords.stream()
                .filter(record -> record.getType() == TransactionRecord.TransactionType.PAYMENT_CHARGE)
                .map(TransactionRecord::getExternalTransactionId)
                .map(Integer::parseInt)
                .findFirst();
    }

    /**
     * Returns the currency of the receipt's payment charge: the first charge record that names one, or
     * {@code "ILS"} when none does. Assumes all payment charges of a receipt use the same currency.
     */
    public String getPaymentCurrency() {
        return transactionRecords.stream()
                .filter(record -> record.getType() == TransactionRecord.TransactionType.PAYMENT_CHARGE)
                .map(TransactionRecord::getCurrency)
                .filter(currency -> currency != null && !currency.isBlank())
                .findFirst()
                .orElse("ILS");
    }

    /**
     * Refunds the whole order and records {@code refundRecord}: every line becomes REFUNDED.
     *
     * @throws IllegalStateException if a line is already refunded or claimed by a refund in flight
     */
    public void markRefunded(TransactionRecord refundRecord) {
        Objects.requireNonNull(refundRecord, "transaction record must not be null");
        refundRecord.checkInvariants();
        refundEveryLine();
        addTransaction(refundRecord);
    }

    private void refundEveryLine() {
        if (hasRefundedOrPendingLines()) {
            throw new IllegalStateException("Receipt " + receiptId
                    + " cannot be refunded as a whole: some lines are already refunded or being refunded");
        }
        for (int i = 0; i < receiptLines.size(); i++) {
            receiptLines.set(i, receiptLines.get(i).withRefundState(LineRefundState.REFUNDED));
        }
        this.isRefunded = true;
    }

    private void moveClaimedLines(int eventId, LineRefundState target) {
        boolean movedAny = false;
        for (int i = 0; i < receiptLines.size(); i++) {
            ReceiptLine line = receiptLines.get(i);
            if (line.getEventId() == eventId && line.getRefundState() == LineRefundState.PENDING) {
                receiptLines.set(i, line.withRefundState(target));
                movedAny = true;
            }
        }
        if (!movedAny) {
            throw new IllegalStateException("Receipt " + receiptId + " holds no refund claim for event " + eventId);
        }
    }





    /**
     * Appends a record to the receipt's transactions.
     *
     * @param record the record to add, not {@code null}
     * @throws NullPointerException  if {@code record} is {@code null}
     * @throws IllegalStateException if the record, or the receipt with it, breaks its invariants
     */
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

        // isFullyRefunded() reads through getReceiptLines(), so a receipt refunded before line state
        // existed (flag set, every line NONE) counts as fully refunded, as everywhere else.
        if (isRefunded != isFullyRefunded()) {
            throw new IllegalStateException("OrderReceipt invariant violated: the order is marked refunded exactly when every line is refunded");
        }

        for (TransactionRecord record : transactionRecords) {
            Objects.requireNonNull(record, "OrderReceipt invariant violated: transaction record must not be null");
            record.checkInvariants();
        }
    }
}
