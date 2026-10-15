package com.ticketing.system.sales.application.service;

import com.ticketing.system.shared.dto.RefundResultDTO;
import com.ticketing.system.shared.exception.RefundFailedException;

/**
 * Checks a payment gateway's refund answer before a refund is recorded (I.3, UC-4). Shared by the
 * member refund and the event-cancellation refund so both trust the gateway on the same terms.
 */
final class RefundResultValidator {

    /**
     * Largest difference between the requested and the refunded amount that still counts as equal.
     * Money is a {@code double} until #73, so an exact comparison would reject correct refunds.
     */
    static final double AMOUNT_TOLERANCE = 0.0001;

    private RefundResultValidator() {
    }

    /**
     * @param receiptId      the receipt being refunded, for the error message
     * @param expectedAmount the amount the gateway was asked to refund
     * @param result         the gateway's answer
     * @throws RefundFailedException if the answer is missing, has no refund transaction id or
     *                               timestamp, or refunded a different amount
     */
    static void validate(int receiptId, double expectedAmount, RefundResultDTO result) {
        if (result == null) {
            throw new RefundFailedException(receiptId, "payment gateway returned null refund result");
        }
        if (result.refundTransactionId() == null || result.refundTransactionId().isBlank()) {
            throw new RefundFailedException(receiptId, "refund transaction id is missing");
        }
        if (Math.abs(result.totalRefunded() - expectedAmount) > AMOUNT_TOLERANCE) {
            throw new RefundFailedException(receiptId, "refund amount mismatch");
        }
        if (result.refundedAt() == null) {
            throw new RefundFailedException(receiptId, "refund timestamp is missing");
        }
    }
}
