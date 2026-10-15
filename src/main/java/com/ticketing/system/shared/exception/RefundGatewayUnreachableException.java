package com.ticketing.system.shared.exception;

/**
 * A refund request never reached the payment gateway (connection refused, connect timeout), so no
 * money moved and the refund can be retried safely. Distinct from a decline, which stays a plain
 * {@link RefundFailedException}, and from {@link RefundOutcomeUnknownException}, where the gateway
 * may have processed the request (ADR-001).
 */
public class RefundGatewayUnreachableException extends RefundFailedException {

    /**
     * @param orderReceiptId the receipt or charge being refunded, for the message
     * @param reason         what failed
     * @param cause          the transport failure
     */
    public RefundGatewayUnreachableException(Object orderReceiptId, String reason, Throwable cause) {
        super(orderReceiptId, reason, cause);
    }
}
