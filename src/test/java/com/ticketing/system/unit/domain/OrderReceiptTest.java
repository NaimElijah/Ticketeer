package com.ticketing.system.unit.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import com.ticketing.system.sales.domain.LineRefundState;
import com.ticketing.system.sales.domain.OrderReceipt;
import com.ticketing.system.sales.domain.ReceiptLine;
import com.ticketing.system.sales.domain.TransactionRecord;
import com.ticketing.system.support.BaseDomainTest;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderReceiptTest extends BaseDomainTest {

    // Distinct, so a receipt built with the two swapped is caught.
    private final int USER_ID = 5;
    private final int RECEIPT_ID = 6;

    // The purchase time comes from the caller's clock (ADR-008); the receipt no longer reads the JVM's.
    @Test
    void GivenPurchaseTime_WhenForMember_ThenTheReceiptRecordsIt() {
        LocalDateTime purchasedAt = LocalDateTime.of(2027, 7, 15, 17, 0);
        ReceiptLine item = new ReceiptLine(1, 20.0, 1, 1, "A1", purchasedAt);

        OrderReceipt receipt = OrderReceipt.forMember(1, 2, 20.0, List.of(item), purchasedAt);

        assertEquals(purchasedAt, receipt.getPurchaseTime());
    }

    @Test
    void GivenAUserId_WhenForMember_ThenSetsUserIdCorrectly() {
        ReceiptLine item = new ReceiptLine(1, 20.0, 1, 1, "A1", AT);
        OrderReceipt receipt = track(OrderReceipt.forMember(RECEIPT_ID, USER_ID, 100.0, new ArrayList<>(List.of(item)), AT));
        assertEquals(USER_ID, receipt.getUserid());
        assertEquals(RECEIPT_ID, receipt.getId());
    }

    @Test
    void GivenAUserId_WhenForMember_ThenIdentifiesAsMemberReceipt() {
        ReceiptLine item = new ReceiptLine(1, 20.0, 1, 1, "A1", AT);
        OrderReceipt receipt = track(OrderReceipt.forMember(RECEIPT_ID, USER_ID, 100.0, new ArrayList<>(List.of(item)), AT));
        assertTrue(receipt.isMemberReceipt());
    }

    @Test
    void GivenAGuestEmail_WhenForGuest_ThenSetsGuestEmailCorrectly() {
        ReceiptLine item = new ReceiptLine(1, 20.0, 1, 1, "A1", AT);
        OrderReceipt receipt = track(OrderReceipt.forGuest("test@test.com", "session-123", RECEIPT_ID, 100.0, new ArrayList<>(List.of(item)), AT));
        assertEquals("test@test.com", receipt.getGuestEmail());
    }

    @Test
    void GivenAGuestEmail_WhenForGuest_ThenIdentifiesAsGuestReceipt() {
        ReceiptLine item = new ReceiptLine(1, 20.0, 1, 1, "A1", AT);
        OrderReceipt receipt = track(OrderReceipt.forGuest("test@test.com", "session-123", RECEIPT_ID, 100.0, new ArrayList<>(List.of(item)), AT));
        assertTrue(receipt.isGuestReceipt());
    }

    @Test
    void GivenAMemberReceipt_WhenMarkRefunded_ThenChangesRefundStateToFullyRefunded() {

        ReceiptLine item = new ReceiptLine(1, 20.0, 1, 1, "A1", AT);
        OrderReceipt receipt = track(OrderReceipt.forMember(RECEIPT_ID, USER_ID, 100.0, new ArrayList<>(List.of(item)), AT));
        receipt.markRefunded();
        assertTrue(receipt.isFullyRefunded());
    }

    // --- Refund state per receipt line (#20, ADR-001) ---

    private static final int CANCELLED_EVENT = 1;
    private static final int OTHER_EVENT = 2;
    private static final LocalDateTime AT = LocalDateTime.of(2026, 6, 1, 12, 0);

    /** A member order with two lines for the cancelled event and one line for another event. */
    private OrderReceipt twoEventOrder() {
        return track(OrderReceipt.forMember(RECEIPT_ID, USER_ID, 100.0, List.of(
                new ReceiptLine(1, 30.0, CANCELLED_EVENT, 1, "A1", AT),
                new ReceiptLine(2, 20.0, CANCELLED_EVENT, 1, "A2", AT),
                new ReceiptLine(3, 50.0, OTHER_EVENT, 1, "B1", AT)), AT));
    }

    private TransactionRecord refundOf(double amount) {
        return TransactionRecord.refund("refund-1", "test-gateway", amount, "ILS", AT);
    }

    private List<LineRefundState> statesOf(OrderReceipt receipt, int eventId) {
        return receipt.getReceiptLines().stream()
                .filter(line -> line.getEventId() == eventId)
                .map(ReceiptLine::getRefundState)
                .toList();
    }

    @Test
    void GivenNewOrder_WhenCreated_ThenNoLineIsRefundedOrPending() {
        OrderReceipt receipt = twoEventOrder();

        assertTrue(receipt.getReceiptLines().stream().allMatch(l -> l.getRefundState() == LineRefundState.NONE));
        assertFalse(receipt.hasRefundedOrPendingLines());
    }

    @Test
    void GivenTwoEventOrder_WhenClaimRefundForEvent_ThenOnlyThatEventsLinesArePendingAndAmountIsTheirSum() {
        OrderReceipt receipt = twoEventOrder();

        OptionalDouble claimed = receipt.claimRefundForEvent(CANCELLED_EVENT);

        assertEquals(50.0, claimed.orElseThrow());
        assertEquals(List.of(LineRefundState.PENDING, LineRefundState.PENDING), statesOf(receipt, CANCELLED_EVENT));
        assertEquals(List.of(LineRefundState.NONE), statesOf(receipt, OTHER_EVENT));
        assertTrue(receipt.hasRefundedOrPendingLines());
    }

    @Test
    void GivenClaimedLines_WhenClaimRefundForEventAgain_ThenNothingIsClaimed() {
        OrderReceipt receipt = twoEventOrder();
        receipt.claimRefundForEvent(CANCELLED_EVENT);

        assertTrue(receipt.claimRefundForEvent(CANCELLED_EVENT).isEmpty());
    }

    @Test
    void GivenClaimedLines_WhenReleaseRefundClaim_ThenLinesCanBeClaimedAgain() {
        OrderReceipt receipt = twoEventOrder();
        receipt.claimRefundForEvent(CANCELLED_EVENT);

        receipt.releaseRefundClaim(CANCELLED_EVENT);

        assertEquals(List.of(LineRefundState.NONE, LineRefundState.NONE), statesOf(receipt, CANCELLED_EVENT));
        assertFalse(receipt.hasRefundedOrPendingLines());
        assertEquals(50.0, receipt.claimRefundForEvent(CANCELLED_EVENT).orElseThrow());
    }

    @Test
    void GivenClaimedLines_WhenAskedForPendingRefunds_ThenOnlyTheClaimedEventHasOne() {
        OrderReceipt receipt = twoEventOrder();
        receipt.claimRefundForEvent(CANCELLED_EVENT);

        assertTrue(receipt.hasPendingRefundFor(CANCELLED_EVENT));
        assertFalse(receipt.hasPendingRefundFor(OTHER_EVENT));
    }

    @Test
    void GivenNoClaim_WhenReleaseRefundClaim_ThenIllegalState() {
        OrderReceipt receipt = twoEventOrder();

        assertThrows(IllegalStateException.class, () -> receipt.releaseRefundClaim(CANCELLED_EVENT));
    }

    @Test
    void GivenClaimedLines_WhenCompleteEventRefund_ThenLinesAreRefundedAndTheRefundIsRecorded() {
        OrderReceipt receipt = twoEventOrder();
        receipt.claimRefundForEvent(CANCELLED_EVENT);

        receipt.completeEventRefund(CANCELLED_EVENT, refundOf(50.0));

        assertEquals(List.of(LineRefundState.REFUNDED, LineRefundState.REFUNDED), statesOf(receipt, CANCELLED_EVENT));
        assertEquals(List.of(LineRefundState.NONE), statesOf(receipt, OTHER_EVENT));
        assertTrue(receipt.getTransactionRecords().stream()
                .anyMatch(t -> t.getType() == TransactionRecord.TransactionType.REFUND && t.getAmount() == 50.0));
        assertFalse(receipt.isFullyRefunded(), "the other event's line is still paid (#21)");
        assertEquals(50.0, receipt.netAmount());
    }

    @Test
    void GivenEveryEventRefunded_WhenCompleteEventRefund_ThenTheOrderIsFullyRefundedAndEarnsNothing() {
        OrderReceipt receipt = twoEventOrder();
        receipt.claimRefundForEvent(CANCELLED_EVENT);
        receipt.completeEventRefund(CANCELLED_EVENT, refundOf(50.0));
        receipt.claimRefundForEvent(OTHER_EVENT);

        receipt.completeEventRefund(OTHER_EVENT, refundOf(50.0));

        assertTrue(receipt.isFullyRefunded());
        assertEquals(0.0, receipt.netAmount());
    }

    @Test
    void GivenClaimedButUnrecordedRefund_WhenNetAmount_ThenTheClaimedLinesStillCount() {
        OrderReceipt receipt = twoEventOrder();
        receipt.claimRefundForEvent(CANCELLED_EVENT);

        assertEquals(100.0, receipt.netAmount());
    }

    @Test
    void GivenRefundedLines_WhenClaimRefundForEvent_ThenNothingIsClaimed() {
        OrderReceipt receipt = twoEventOrder();
        receipt.claimRefundForEvent(CANCELLED_EVENT);
        receipt.completeEventRefund(CANCELLED_EVENT, refundOf(50.0));

        assertTrue(receipt.claimRefundForEvent(CANCELLED_EVENT).isEmpty());
    }

    @Test
    void GivenNoClaim_WhenCompleteEventRefund_ThenIllegalState() {
        OrderReceipt receipt = twoEventOrder();

        assertThrows(IllegalStateException.class, () -> receipt.completeEventRefund(CANCELLED_EVENT, refundOf(50.0)));
    }

    @Test
    void GivenANonRefundRecord_WhenCompleteEventRefund_ThenIllegalArgument() {
        OrderReceipt receipt = twoEventOrder();
        receipt.claimRefundForEvent(CANCELLED_EVENT);
        TransactionRecord charge = TransactionRecord.paymentCharge(7, "test-gateway", 50.0, "ILS", AT);

        assertThrows(IllegalArgumentException.class, () -> receipt.completeEventRefund(CANCELLED_EVENT, charge));

        assertEquals(List.of(LineRefundState.PENDING, LineRefundState.PENDING), statesOf(receipt, CANCELLED_EVENT),
                "a refused record leaves the claim in flight");
        assertTrue(receipt.getTransactionRecords().isEmpty(), "a refused record is not added");
    }

    @Test
    void GivenClaimedFreeLines_WhenCompleteUnchargedEventRefund_ThenLinesAreRefundedWithoutARecord() {
        OrderReceipt receipt = track(OrderReceipt.forMember(RECEIPT_ID, USER_ID, 0.0,
                List.of(new ReceiptLine(1, 0.0, CANCELLED_EVENT, 1, "A1", AT)),
                AT));
        assertEquals(0.0, receipt.claimRefundForEvent(CANCELLED_EVENT).orElseThrow());

        receipt.completeUnchargedEventRefund(CANCELLED_EVENT);

        assertEquals(List.of(LineRefundState.REFUNDED), statesOf(receipt, CANCELLED_EVENT));
        assertTrue(receipt.getTransactionRecords().isEmpty());
    }

    @Test
    void GivenWholeOrderRefund_WhenMarkRefunded_ThenEveryLineIsRefundedAndNothingIsLeftToClaim() {
        OrderReceipt receipt = twoEventOrder();

        receipt.markRefunded(refundOf(100.0));

        assertTrue(receipt.getReceiptLines().stream().allMatch(ReceiptLine::isRefunded));
        assertTrue(receipt.claimRefundForEvent(CANCELLED_EVENT).isEmpty());
    }

    // ---- whose receipt it is, and what it holds (#189) ----

    private static final String GUEST_EMAIL = "guest@test.com";
    private static final String GUEST_SESSION = "session-123";

    private static List<ReceiptLine> oneLine() {
        return List.of(new ReceiptLine(1, 20.0, CANCELLED_EVENT, 1, "A1", AT));
    }

    @ParameterizedTest(name = "userId={0}")
    @ValueSource(ints = {0, -1})
    void GivenAUserIdBelowOne_WhenForMember_ThenRefused(int userId) {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> OrderReceipt.forMember(RECEIPT_ID, userId, 20.0, oneLine(), AT));

        assertEquals("userid must be positive", refused.getMessage());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void GivenNoGuestEmail_WhenForGuest_ThenRefused(String email) {
        assertThrows(IllegalArgumentException.class,
                () -> OrderReceipt.forGuest(email, GUEST_SESSION, RECEIPT_ID, 20.0, oneLine(), AT));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void GivenNoGuestSession_WhenForGuest_ThenRefused(String sessionId) {
        assertThrows(IllegalArgumentException.class,
                () -> OrderReceipt.forGuest(GUEST_EMAIL, sessionId, RECEIPT_ID, 20.0, oneLine(), AT));
    }

    @ParameterizedTest
    @NullAndEmptySource
    void GivenNoLines_WhenForMember_ThenRefused(List<ReceiptLine> lines) {
        assertThrows(IllegalArgumentException.class,
                () -> OrderReceipt.forMember(RECEIPT_ID, USER_ID, 20.0, lines, AT));
    }

    @Test
    void GivenANegativeTotal_WhenForMember_ThenRefused() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> OrderReceipt.forMember(RECEIPT_ID, USER_ID, -0.01, oneLine(), AT));

        assertEquals("totalPrice must be non-negative", refused.getMessage());
    }

    @ParameterizedTest(name = "receiptId={0}")
    @ValueSource(ints = {0, -1})
    void GivenAReceiptIdBelowOne_WhenForMember_ThenRefused(int receiptId) {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> OrderReceipt.forMember(receiptId, USER_ID, 20.0, oneLine(), AT));

        assertEquals("receiptId must be positive", refused.getMessage());
    }

    @Test
    void GivenAnEventNotOnTheReceipt_WhenClaimRefundForEvent_ThenNothingIsClaimedAndNoLineChanges() {
        OrderReceipt receipt = twoEventOrder();
        int eventNotOnTheReceipt = OTHER_EVENT + 1;

        assertTrue(receipt.claimRefundForEvent(eventNotOnTheReceipt).isEmpty());

        assertTrue(receipt.getReceiptLines().stream().allMatch(l -> l.getRefundState() == LineRefundState.NONE));
        assertFalse(receipt.hasRefundedOrPendingLines());
    }

    @Test
    void GivenAChargeInDollars_WhenGetPaymentCurrency_ThenDollars() {
        OrderReceipt receipt = track(OrderReceipt.forMember(RECEIPT_ID, USER_ID, 20.0, oneLine(), AT,
                List.of(TransactionRecord.paymentCharge(7, "test-gateway", 20.0, "USD", AT))));

        assertEquals("USD", receipt.getPaymentCurrency());
    }

    // Pins today's fallback for a receipt with no recorded charge (a free order, or one stored before charges
    // were recorded).
    @Test
    void GivenNoCharge_WhenGetPaymentCurrency_ThenShekels() {
        OrderReceipt receipt = track(OrderReceipt.forMember(RECEIPT_ID, USER_ID, 0.0,
                List.of(new ReceiptLine(1, 0.0, CANCELLED_EVENT, 1, "A1", AT)), AT));

        assertEquals("ILS", receipt.getPaymentCurrency());
    }

    @Test
    void GivenLinesClaimedByACancellation_WhenMarkRefunded_ThenIllegalState() {
        OrderReceipt receipt = twoEventOrder();
        receipt.claimRefundForEvent(CANCELLED_EVENT);

        assertThrows(IllegalStateException.class, () -> receipt.markRefunded(refundOf(100.0)));
    }
}
