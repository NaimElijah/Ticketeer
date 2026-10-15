package com.ticketing.system.unit.domain;

import org.junit.jupiter.api.Test;

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

    private final int USER_ID = 5;
    private final int RECEIPT_ID = 5;

    @Test
    void testForMemberSetsUserIdCorrectly() {
        ReceiptLine item = new ReceiptLine(1, 20.0, 1, 1, "A1", LocalDateTime.now());
        OrderReceipt receipt = track(OrderReceipt.forMember(RECEIPT_ID, USER_ID, 100.0, new ArrayList<>(List.of(item))));
        assertEquals(5, receipt.getUserid());
    }

    @Test
    void testForMemberIdentifiesAsMemberReceipt() {
        ReceiptLine item = new ReceiptLine(1, 20.0, 1, 1, "A1", LocalDateTime.now());
        OrderReceipt receipt = track(OrderReceipt.forMember(RECEIPT_ID, USER_ID, 100.0, new ArrayList<>(List.of(item))));
        assertTrue(receipt.isMemberReceipt());
    }

    @Test
    void testForGuestSetsGuestEmailCorrectly() {
        ReceiptLine item = new ReceiptLine(1, 20.0, 1, 1, "A1", LocalDateTime.now());
        OrderReceipt receipt = track(OrderReceipt.forGuest("test@test.com", "session-123", RECEIPT_ID, 100.0, new ArrayList<>(List.of(item))));
        assertEquals("test@test.com", receipt.getGuestEmail());
    }

    @Test
    void testForGuestIdentifiesAsGuestReceipt() {
        ReceiptLine item = new ReceiptLine(1, 20.0, 1, 1, "A1", LocalDateTime.now());
        OrderReceipt receipt = track(OrderReceipt.forGuest("test@test.com", "session-123", RECEIPT_ID, 100.0, new ArrayList<>(List.of(item))));
        assertTrue(receipt.isGuestReceipt());
    }

    @Test
    void testMarkRefundedChangesRefundStateToTrue() {

        ReceiptLine item = new ReceiptLine(1, 20.0, 1, 1, "A1", LocalDateTime.now());
        OrderReceipt receipt = track(OrderReceipt.forMember(RECEIPT_ID, USER_ID, 100.0, new ArrayList<>(List.of(item))));
        receipt.markRefunded();
        assertTrue(receipt.wasRefunded());
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
                new ReceiptLine(3, 50.0, OTHER_EVENT, 1, "B1", AT))));
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
        assertTrue(receipt.wasRefunded());
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
    }

    @Test
    void GivenClaimedFreeLines_WhenCompleteUnchargedEventRefund_ThenLinesAreRefundedWithoutARecord() {
        OrderReceipt receipt = track(OrderReceipt.forMember(RECEIPT_ID, USER_ID, 0.0,
                List.of(new ReceiptLine(1, 0.0, CANCELLED_EVENT, 1, "A1", AT))));
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

    @Test
    void GivenLinesClaimedByACancellation_WhenMarkRefunded_ThenIllegalState() {
        OrderReceipt receipt = twoEventOrder();
        receipt.claimRefundForEvent(CANCELLED_EVENT);

        assertThrows(IllegalStateException.class, () -> receipt.markRefunded(refundOf(100.0)));
    }
}
