package com.ticketing.system.shared.exception;

/**
 * A refund request may have been carried out, but no usable answer came back: a read timeout, a
 * malformed or mismatched result, or an unexpected error after the request was sent. Retrying could
 * refund the buyer twice, so a caller must not treat the refund as declined (ADR-001).
 */
public class RefundOutcomeUnknownException extends RefundFailedException {

    /**
     * @param orderReceiptId the receipt or charge being refunded, for the message
     * @param reason         what made the outcome unknown
     * @param cause          the underlying failure
     */
    public RefundOutcomeUnknownException(Object orderReceiptId, String reason, Throwable cause) {
        super(orderReceiptId, reason, cause);
    }
}
