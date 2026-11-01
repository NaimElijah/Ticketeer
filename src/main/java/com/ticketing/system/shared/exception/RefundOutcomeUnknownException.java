package com.ticketing.system.shared.exception;

/**
 * A refund request may have been carried out, but no usable answer came back: a read timeout, a
 * malformed or mismatched result, or an unexpected error after the request was sent. Retrying could
 * refund the buyer twice, so a caller must not treat the refund as declined (ADR-001).
 */
public class RefundOutcomeUnknownException extends RefundFailedException {

    /**
     * @param orderReceiptId the receipt being refunded, named as an order in the message
     * @param reason         what made the outcome unknown
     * @param cause          the underlying failure
     */
    public RefundOutcomeUnknownException(Object orderReceiptId, String reason, Throwable cause) {
        super(orderReceiptId, reason, cause);
    }

    private RefundOutcomeUnknownException(Subject subject, Object id, String reason, Throwable cause) {
        super(subject, id, reason, cause);
    }

    /**
     * A refund of a charge whose outcome is unknown, for a payment gateway, which refunds by payment
     * transaction id.
     *
     * @param paymentTransactionId the charge being refunded
     * @param reason               what made the outcome unknown
     * @param cause                the underlying failure
     * @return the exception, its message naming the payment transaction
     */
    public static RefundOutcomeUnknownException forPaymentTransaction(int paymentTransactionId, String reason,
            Throwable cause) {
        return new RefundOutcomeUnknownException(Subject.PAYMENT_TRANSACTION, paymentTransactionId, reason, cause);
    }
}
