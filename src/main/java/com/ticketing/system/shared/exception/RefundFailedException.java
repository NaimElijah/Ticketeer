package com.ticketing.system.shared.exception;

// Thrown when an auto-refund itself fails (gateway down for the refund call).
// Distinct from PaymentGatewayException: this is the *refund* side, which has
// elevated SLR.5 fault-tolerance significance — a customer was charged but not refunded.
// UC-4. Listeners should escalate (logging, admin alert, retry queue).
// The message names what failed to be refunded: an order (receipt) where a service refunds one, a payment
// transaction where a gateway refunds by charge id (forPaymentTransaction), so nobody looks for an order by a
// charge id (NaimElijah/Ticketeer#187).
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
