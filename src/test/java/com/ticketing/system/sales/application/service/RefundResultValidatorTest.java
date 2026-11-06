package com.ticketing.system.sales.application.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.ticketing.system.shared.dto.RefundResultDTO;
import com.ticketing.system.shared.exception.RefundFailedException;

/**
 * The checks both refund paths apply to a payment gateway's refund answer before recording it (I.3, UC-4):
 * an answer is accepted only with a refund transaction id, a timestamp, and the amount asked for, to within
 * {@link RefundResultValidator#AMOUNT_TOLERANCE}. What each path does with a rejected answer is its own
 * concern, pinned in its own tests.
 *
 * <p>Tests the package-private validator directly, so every rejection is pinned once, whichever path
 * reaches it.
 */
class RefundResultValidatorTest {

    private static final int RECEIPT_ID = 7;
    private static final double REQUESTED = 150.0;
    private static final String REFUND_ID = "refund-1";
    private static final LocalDateTime REFUNDED_AT = LocalDateTime.of(2026, 6, 1, 12, 0);

    private static RefundResultDTO answer(String refundTransactionId, double totalRefunded, LocalDateTime refundedAt) {
        return new RefundResultDTO(refundTransactionId, "555", totalRefunded, refundedAt, List.of(), List.of());
    }

    @Test
    void GivenACompleteAnswerForTheRequestedAmount_WhenValidate_ThenItIsAccepted() {
        assertThatCode(() -> RefundResultValidator.validate(RECEIPT_ID, REQUESTED,
                answer(REFUND_ID, REQUESTED, REFUNDED_AT)))
                .doesNotThrowAnyException();
    }

    @Test
    void GivenNoAnswer_WhenValidate_ThenRefundFailedNamingTheReceipt() {
        assertThatThrownBy(() -> RefundResultValidator.validate(RECEIPT_ID, REQUESTED, null))
                .isExactlyInstanceOf(RefundFailedException.class)
                .hasMessage("Refund failed for order 7: payment gateway returned null refund result");
    }

    @Test
    void GivenAnAnswerWithoutARefundTransactionId_WhenValidate_ThenRefundFailed() {
        assertThatThrownBy(() -> RefundResultValidator.validate(RECEIPT_ID, REQUESTED,
                answer(null, REQUESTED, REFUNDED_AT)))
                .isExactlyInstanceOf(RefundFailedException.class)
                .hasMessageContaining("refund transaction id is missing");
    }

    @Test
    void GivenAnAnswerWithABlankRefundTransactionId_WhenValidate_ThenRefundFailed() {
        assertThatThrownBy(() -> RefundResultValidator.validate(RECEIPT_ID, REQUESTED,
                answer(" ", REQUESTED, REFUNDED_AT)))
                .isExactlyInstanceOf(RefundFailedException.class)
                .hasMessageContaining("refund transaction id is missing");
    }

    @Test
    void GivenAnAnswerWithoutATimestamp_WhenValidate_ThenRefundFailed() {
        assertThatThrownBy(() -> RefundResultValidator.validate(RECEIPT_ID, REQUESTED,
                answer(REFUND_ID, REQUESTED, null)))
                .isExactlyInstanceOf(RefundFailedException.class)
                .hasMessageContaining("refund timestamp is missing");
    }

    @Test
    void GivenAnAmountOffByLessThanTheTolerance_WhenValidate_ThenItIsAccepted() {
        double withinTolerance = REQUESTED - RefundResultValidator.AMOUNT_TOLERANCE / 2;

        assertThatCode(() -> RefundResultValidator.validate(RECEIPT_ID, REQUESTED,
                answer(REFUND_ID, withinTolerance, REFUNDED_AT)))
                .doesNotThrowAnyException();
    }

    @Test
    void GivenLessThanRequestedBeyondTheTolerance_WhenValidate_ThenRefundFailed() {
        double tooLittle = REQUESTED - RefundResultValidator.AMOUNT_TOLERANCE * 2;

        assertThatThrownBy(() -> RefundResultValidator.validate(RECEIPT_ID, REQUESTED,
                answer(REFUND_ID, tooLittle, REFUNDED_AT)))
                .isExactlyInstanceOf(RefundFailedException.class)
                .hasMessageContaining("refund amount mismatch");
    }

    @Test
    void GivenMoreThanRequestedBeyondTheTolerance_WhenValidate_ThenRefundFailed() {
        double tooMuch = REQUESTED + RefundResultValidator.AMOUNT_TOLERANCE * 2;

        assertThatThrownBy(() -> RefundResultValidator.validate(RECEIPT_ID, REQUESTED,
                answer(REFUND_ID, tooMuch, REFUNDED_AT)))
                .isExactlyInstanceOf(RefundFailedException.class)
                .hasMessageContaining("refund amount mismatch");
    }
}
