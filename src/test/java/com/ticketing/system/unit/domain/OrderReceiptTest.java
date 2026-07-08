package com.ticketing.system.unit.domain;

import org.junit.jupiter.api.Test;

import com.ticketing.system.sales.domain.OrderReceipt;
import com.ticketing.system.sales.domain.ReceiptLine;
import com.ticketing.system.support.BaseDomainTest;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}