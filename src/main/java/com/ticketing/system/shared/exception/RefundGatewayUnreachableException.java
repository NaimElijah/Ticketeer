package com.ticketing.system.shared.exception;

/**
 * A refund request never reached the payment gateway (connection refused, connect timeout), so no
 * money moved and the refund can be retried safely. Distinct from a decline, which stays a plain
 * {@link RefundFailedException}, and from {@link RefundOutcomeUnknownException}, where the gateway
 * may have processed the request (ADR-001).
 */
public class RefundGatewayUnreachableException extends RefundFailedException {

    /**
     * @param orderReceiptId the receipt being refunded, named as an order in the message
     * @param reason         what failed
     * @param cause          the transport failure
     */
    public RefundGatewayUnreachableException(Object orderReceiptId, String reason, Throwable cause) {
        super(orderReceiptId, reason, cause);
    }

    private RefundGatewayUnreachableException(Subject subject, Object id, String reason, Throwable cause) {
        super(subject, id, reason, cause);
    }

    /**
     * A refund of a charge that never reached the gateway, for a payment gateway, which refunds by
     * payment transaction id.
     *
     * @param paymentTransactionId the charge being refunded
     * @param reason               what failed
     * @param cause                the transport failure
     * @return the exception, its message naming the payment transaction
     */
    public static RefundGatewayUnreachableException forPaymentTransaction(int paymentTransactionId, String reason,
            Throwable cause) {
        return new RefundGatewayUnreachableException(Subject.PAYMENT_TRANSACTION, paymentTransactionId, reason, cause);
    }
}
