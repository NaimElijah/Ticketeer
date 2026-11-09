package com.ticketing.system.shared.exception;

/**
 * Thrown when a refund fails: the gateway rejected it, its result failed validation, the
 * receipt holds no payment transaction to refund, or a cancelled event's refunds left receipts
 * unrefunded. UC-4.
 *
 * <p>Distinct from {@link PaymentGatewayException}: this is the <em>refund</em> side, which has
 * elevated SLR.5 fault-tolerance significance, because a customer was charged but not refunded.
 * {@link RefundGatewayUnreachableException} and {@link RefundOutcomeUnknownException} are the
 * subtypes for a request that never reached the gateway and for one that may have been processed.
 *
 * <p>A gateway refunds by charge id, so {@code forPaymentTransaction} labels its subject a payment
 * transaction, not an order, and nobody looks for an order by a charge id
 * (NaimElijah/Ticketeer#187).
 *
 * <p>{@code RefundPresenter} shows the member fixed text. A cancel shows its owner the plain summary of
 * the receipts it could not refund as written, and fixed text for the two subtypes.
 */
public class RefundFailedException extends DomainException {

    /** What a refund failed for, as its message names it. */
    protected enum Subject {
        ORDER("order"),
        PAYMENT_TRANSACTION("payment transaction");

        private final String label;

        Subject(String label) {
            this.label = label;
        }
    }

    /**
     * @param orderReceiptId the receipt, or receipts, being refunded, named as an order in the message
     * @param reason         what failed
     */
    public RefundFailedException(Object orderReceiptId, String reason) {
        this(Subject.ORDER, orderReceiptId, reason, null);
    }

    /**
     * @param orderReceiptId the receipt being refunded, named as an order in the message
     * @param reason         what failed
     * @param cause          the underlying failure
     */
    public RefundFailedException(Object orderReceiptId, String reason, Throwable cause) {
        this(Subject.ORDER, orderReceiptId, reason, cause);
    }

    /**
     * @param subject what failed to be refunded
     * @param id      its id
     * @param reason  what failed
     * @param cause   the underlying failure, or {@code null}
     */
    protected RefundFailedException(Subject subject, Object id, String reason, Throwable cause) {
        super("Refund failed for " + subject.label + " " + id + ": " + reason, cause);
    }

    /**
     * A refund of a charge that failed, for a payment gateway, which refunds by payment transaction id.
     *
     * @param paymentTransactionId the charge being refunded
     * @param reason               what failed
     * @return the exception, its message naming the payment transaction
     */
    public static RefundFailedException forPaymentTransaction(int paymentTransactionId, String reason) {
        return new RefundFailedException(Subject.PAYMENT_TRANSACTION, paymentTransactionId, reason, null);
    }
}
