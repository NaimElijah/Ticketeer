package com.ticketing.system.unit.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;

import com.ticketing.system.sales.application.port.out.OrderReceiptRepository;
import com.ticketing.system.sales.application.port.out.PaymentGateway;
import com.ticketing.system.sales.application.port.out.TicketRepository;
import com.ticketing.system.sales.application.service.EventCancellationRefundService;
import com.ticketing.system.sales.domain.LineRefundState;
import com.ticketing.system.sales.domain.OrderReceipt;
import com.ticketing.system.sales.domain.ReceiptLine;
import com.ticketing.system.sales.domain.Ticket;
import com.ticketing.system.sales.domain.TicketStatus;
import com.ticketing.system.sales.domain.TransactionRecord;
import com.ticketing.system.shared.dto.RefundResultDTO;
import com.ticketing.system.shared.event.EventCancelledNotice;
import com.ticketing.system.shared.exception.RefundFailedException;
import com.ticketing.system.shared.exception.RefundGatewayUnreachableException;
import com.ticketing.system.shared.exception.RefundOutcomeUnknownException;
import com.ticketing.system.testutil.TestTransactions;

/**
 * Unit tests for {@link EventCancellationRefundService}: the refund / ticket-flip / holder-notice flow
 * run when an event is cancelled (UC-4 / UC-19), made exactly-once and resumable by #20 (ADR-001).
 * The sales ports are mocked over real {@link OrderReceipt}s, so the line refund states are the real
 * domain behaviour; the commit-time JPA behaviour is in {@code EventCancellationRefundConcurrencyTest}.
 */
class EventCancellationRefundServiceTest {

    private static final int EVENT_ID = 10;
    private static final int OTHER_EVENT_ID = 11;
    private static final int ZONE_ID = 5;
    private static final String EVENT_NAME = "Concert";
    private static final LocalDateTime AT = LocalDateTime.of(2026, 6, 1, 12, 0);

    private OrderReceiptRepository orderReceiptRepository;
    private TicketRepository ticketRepository;
    private PaymentGateway paymentGateway;
    private ApplicationEventPublisher eventPublisher;
    private EventCancellationRefundService service;

    private final List<OrderReceipt> receipts = new ArrayList<>();
    private final List<Ticket> tickets = new ArrayList<>();

    @BeforeEach
    void setUp() {
        orderReceiptRepository = mock(OrderReceiptRepository.class);
        ticketRepository = mock(TicketRepository.class);
        paymentGateway = mock(PaymentGateway.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        service = new EventCancellationRefundService(orderReceiptRepository, ticketRepository, paymentGateway,
                eventPublisher, TestTransactions.noOpManager());

        when(orderReceiptRepository.findByEventId(EVENT_ID)).thenAnswer(inv -> List.copyOf(receipts));
        when(orderReceiptRepository.findByOrderReceiptId(anyInt())).thenAnswer(inv -> receipts.stream()
                .filter(r -> r.getId() == (int) inv.getArgument(0)).findFirst());
        when(ticketRepository.findByOrderReceiptId(anyInt())).thenAnswer(inv -> tickets.stream()
                .filter(t -> t.getOrderReceiptId() == (int) inv.getArgument(0)).toList());
        when(ticketRepository.findByEventId(EVENT_ID)).thenAnswer(inv -> tickets.stream()
                .filter(t -> t.getEventId() == EVENT_ID).toList());
        when(paymentGateway.getId()).thenReturn("test-gateway");
        when(paymentGateway.refund(anyInt(), anyDouble())).thenAnswer(inv -> refunded(inv.getArgument(0), inv.getArgument(1)));
    }

    // ---- fixtures -------------------------------------------------------

    private static RefundResultDTO refunded(int chargeId, double amount) {
        return new RefundResultDTO("refund-" + chargeId, String.valueOf(chargeId), amount, AT, List.of(), List.of());
    }

    private static int chargeOf(int receiptId) {
        return 1000 + receiptId;
    }

    /** A member receipt of {@code holderUserId} with one paid ticket per price, all for EVENT_ID. */
    private OrderReceipt paidReceipt(int receiptId, int holderUserId, double... prices) {
        List<ReceiptLine> lines = new ArrayList<>();
        double total = 0;
        for (int i = 0; i < prices.length; i++) {
            int ticketId = receiptId * 10 + i;
            lines.add(new ReceiptLine(ticketId, prices[i], EVENT_ID, ZONE_ID, "A" + ticketId, AT));
            tickets.add(new Ticket(EVENT_ID, ZONE_ID, receiptId, "A" + ticketId, prices[i], ticketId, "BC" + ticketId));
            total += prices[i];
        }
        OrderReceipt receipt = OrderReceipt.forMember(receiptId, holderUserId, total, lines,
                List.of(TransactionRecord.paymentCharge(chargeOf(receiptId), "test-gateway", total, "ILS", AT)));
        receipts.add(receipt);
        return receipt;
    }

    private void refundCancelledEvent() {
        service.refundCancelledEvent(EVENT_ID, EVENT_NAME);
    }

    private static boolean allLinesRefunded(OrderReceipt receipt) {
        return receipt.getReceiptLines().stream().allMatch(ReceiptLine::isRefunded);
    }

    private static boolean allLinesPending(OrderReceipt receipt) {
        return receipt.getReceiptLines().stream().allMatch(l -> l.getRefundState() == LineRefundState.PENDING);
    }

    private static boolean noLineTouched(OrderReceipt receipt) {
        return receipt.getReceiptLines().stream().allMatch(l -> l.getRefundState() == LineRefundState.NONE);
    }

    private List<TicketStatus> ticketStatusesOf(int receiptId) {
        return tickets.stream().filter(t -> t.getOrderReceiptId() == receiptId).map(Ticket::getStatus).toList();
    }

    // ---- refunds --------------------------------------------------------

    @Test
    void GivenPaidReceipt_WhenRefundCancelledEvent_ThenItsLinesAreRefundedAndTheRefundIsRecorded() {
        OrderReceipt receipt = paidReceipt(1, 7, 60.0, 40.0);

        refundCancelledEvent();

        verify(paymentGateway).refund(chargeOf(1), 100.0);
        assertTrue(allLinesRefunded(receipt));
        assertTrue(receipt.getTransactionRecords().stream()
                .anyMatch(t -> t.getType() == TransactionRecord.TransactionType.REFUND && t.getAmount() == 100.0));
    }

    @Test
    void GivenGatewayDeclinesTheSecondReceipt_WhenRefundedAgain_ThenEachReceiptIsRefundedExactlyOnce() {
        OrderReceipt first = paidReceipt(1, 7, 50.0);
        OrderReceipt second = paidReceipt(2, 8, 50.0);
        OrderReceipt third = paidReceipt(3, 9, 50.0);
        when(paymentGateway.refund(eq(chargeOf(2)), anyDouble()))
                .thenThrow(new RefundFailedException(2, "declined"))
                .thenAnswer(inv -> refunded(chargeOf(2), 50.0));

        RefundFailedException declined = assertThrows(RefundFailedException.class, this::refundCancelledEvent);

        assertTrue(declined.getMessage().contains("[2]"), declined.getMessage());
        assertTrue(allLinesRefunded(first));
        assertEquals(List.of(TicketStatus.REFUNDED), ticketStatusesOf(1));
        assertTrue(noLineTouched(second), "the declined claim is released for a retry");
        assertEquals(List.of(TicketStatus.PAID), ticketStatusesOf(2));
        assertTrue(allLinesRefunded(third), "a decline specific to one receipt does not block the others");

        refundCancelledEvent();

        verify(paymentGateway, times(1)).refund(chargeOf(1), 50.0);
        verify(paymentGateway, times(2)).refund(chargeOf(2), 50.0);
        verify(paymentGateway, times(1)).refund(chargeOf(3), 50.0);
        assertTrue(allLinesRefunded(second));
        assertTrue(allLinesRefunded(third));
        assertTrue(tickets.stream().allMatch(t -> t.getStatus() == TicketStatus.REFUNDED));
    }

    // Review finding 2 on #129: a receipt that is declined on every attempt must not strand the rest.
    @Test
    void GivenAReceiptThatIsAlwaysDeclined_WhenRefundCancelledEvent_ThenEveryOtherReceiptIsStillRefunded() {
        paidReceipt(1, 7, 50.0);
        OrderReceipt second = paidReceipt(2, 8, 50.0);
        OrderReceipt third = paidReceipt(3, 9, 50.0);
        Ticket unsold = new Ticket(EVENT_ID, ZONE_ID, 99, "Z9", 50.0, 99, "BC99");
        unsold.release();
        tickets.add(unsold);
        when(paymentGateway.refund(eq(chargeOf(1)), anyDouble())).thenThrow(new RefundFailedException(1, "declined"));

        assertThrows(RefundFailedException.class, this::refundCancelledEvent);
        assertThrows(RefundFailedException.class, this::refundCancelledEvent);

        assertTrue(allLinesRefunded(second));
        assertTrue(allLinesRefunded(third));
        assertEquals(TicketStatus.VOIDED, unsold.getStatus());
        verify(paymentGateway, times(1)).refund(chargeOf(2), 50.0);
        verify(paymentGateway, times(1)).refund(chargeOf(3), 50.0);
    }

    @Test
    void GivenReceiptsInAnyOrder_WhenRefundCancelledEvent_ThenTheyAreRefundedInReceiptIdOrder() {
        paidReceipt(3, 9, 50.0);
        paidReceipt(1, 7, 50.0);
        paidReceipt(2, 8, 50.0);

        refundCancelledEvent();

        InOrder inOrder = inOrder(paymentGateway);
        inOrder.verify(paymentGateway).refund(chargeOf(1), 50.0);
        inOrder.verify(paymentGateway).refund(chargeOf(2), 50.0);
        inOrder.verify(paymentGateway).refund(chargeOf(3), 50.0);
    }

    @Test
    void GivenRefundedEvent_WhenRefundCancelledEventAgain_ThenNothingIsRefundedTwice() {
        paidReceipt(1, 7, 50.0);
        refundCancelledEvent();

        refundCancelledEvent();

        verify(paymentGateway, times(1)).refund(anyInt(), anyDouble());
    }

    @Test
    void GivenMultiEventReceipt_WhenRefundCancelledEvent_ThenOnlyThatEventsLinesAreRefunded() {
        OrderReceipt receipt = OrderReceipt.forMember(1, 7, 100.0,
                List.of(new ReceiptLine(10, 30.0, EVENT_ID, ZONE_ID, "A1", AT),
                        new ReceiptLine(11, 70.0, OTHER_EVENT_ID, ZONE_ID, "B1", AT)),
                List.of(TransactionRecord.paymentCharge(chargeOf(1), "test-gateway", 100.0, "ILS", AT)));
        receipts.add(receipt);
        Ticket cancelledEventTicket = new Ticket(EVENT_ID, ZONE_ID, 1, "A1", 30.0, 10, "BC10");
        Ticket otherEventTicket = new Ticket(OTHER_EVENT_ID, ZONE_ID, 1, "B1", 70.0, 11, "BC11");
        tickets.addAll(List.of(cancelledEventTicket, otherEventTicket));

        refundCancelledEvent();

        verify(paymentGateway).refund(chargeOf(1), 30.0);
        assertEquals(LineRefundState.REFUNDED, receipt.getReceiptLines().get(0).getRefundState());
        assertEquals(LineRefundState.NONE, receipt.getReceiptLines().get(1).getRefundState());
        assertEquals(TicketStatus.REFUNDED, cancelledEventTicket.getStatus());
        assertEquals(TicketStatus.PAID, otherEventTicket.getStatus());
    }

    @Test
    void GivenReceiptAlreadyRefundedByTheMember_WhenRefundCancelledEvent_ThenTheGatewayIsNotCalled() {
        OrderReceipt receipt = paidReceipt(1, 7, 50.0);
        receipt.markRefunded(TransactionRecord.refund("member-refund", "test-gateway", 50.0, "ILS", AT));

        refundCancelledEvent();

        verify(paymentGateway, never()).refund(anyInt(), anyDouble());
    }

    @Test
    void GivenFreeTickets_WhenRefundCancelledEvent_ThenLinesAreRefundedWithoutAGatewayCall() {
        OrderReceipt receipt = OrderReceipt.forMember(1, 7, 0.0,
                List.of(new ReceiptLine(10, 0.0, EVENT_ID, ZONE_ID, "A1", AT)));
        receipts.add(receipt);

        refundCancelledEvent();

        verify(paymentGateway, never()).refund(anyInt(), anyDouble());
        assertTrue(allLinesRefunded(receipt));
    }

    @Test
    void GivenPaidReceiptWithoutACharge_WhenRefundCancelledEvent_ThenRefundFailsAndNothingIsClaimed() {
        OrderReceipt receipt = OrderReceipt.forMember(1, 7, 50.0,
                List.of(new ReceiptLine(10, 50.0, EVENT_ID, ZONE_ID, "A1", AT)));
        receipts.add(receipt);

        assertThrows(RefundFailedException.class, this::refundCancelledEvent);

        assertTrue(noLineTouched(receipt));
        verify(paymentGateway, never()).refund(anyInt(), anyDouble());
    }

    // ADR-001: once the gateway has answered, the money may have moved, so a rejected answer keeps the
    // claim and a retry never asks the gateway again (review finding 1 on #129).
    @Test
    void GivenGatewayRefundsTheWrongAmount_WhenRefundedAgain_ThenTheClaimStaysPendingAndTheGatewayIsNotCalledAgain() {
        OrderReceipt receipt = paidReceipt(1, 7, 50.0);
        when(paymentGateway.refund(anyInt(), anyDouble())).thenReturn(refunded(chargeOf(1), 49.0));

        assertThrows(RefundOutcomeUnknownException.class, this::refundCancelledEvent);
        assertTrue(allLinesPending(receipt));

        assertThrows(RefundFailedException.class, this::refundCancelledEvent, "the retry reports it still pending");

        verify(paymentGateway, times(1)).refund(anyInt(), anyDouble());
        assertTrue(allLinesPending(receipt));
    }

    // Review finding 3 on #129: a claim this run did not make and cannot resolve, whether another run
    // holds it or a crash stranded it, must be reported instead of the event being called refunded.
    @Test
    void GivenAClaimLeftPendingByAnotherRun_WhenRefundCancelledEvent_ThenTheOthersAreRefundedAndTheRunFailsNamingIt() {
        OrderReceipt stranded = paidReceipt(1, 7, 50.0);
        stranded.claimRefundForEvent(EVENT_ID);
        OrderReceipt other = paidReceipt(2, 8, 50.0);

        RefundFailedException pending = assertThrows(RefundFailedException.class, this::refundCancelledEvent);

        assertTrue(pending.getMessage().contains("[1]"), pending.getMessage());
        assertTrue(allLinesRefunded(other));
        verify(paymentGateway, never()).refund(eq(chargeOf(1)), anyDouble());
    }

    @Test
    void GivenGatewayOutcomeUnknown_WhenRefundCancelledEvent_ThenTheClaimStaysPendingAndTheRunStops() {
        OrderReceipt first = paidReceipt(1, 7, 50.0);
        OrderReceipt second = paidReceipt(2, 8, 50.0);
        when(paymentGateway.refund(eq(chargeOf(1)), anyDouble()))
                .thenThrow(new RefundOutcomeUnknownException(chargeOf(1), "timed out", new RuntimeException()));

        assertThrows(RefundOutcomeUnknownException.class, this::refundCancelledEvent);

        assertTrue(allLinesPending(first));
        assertTrue(noLineTouched(second));
        verify(paymentGateway, never()).refund(eq(chargeOf(2)), anyDouble());
    }

    @Test
    void GivenUnexpectedGatewayError_WhenRefundCancelledEvent_ThenTheOutcomeIsUnknownAndTheClaimStaysPending() {
        OrderReceipt receipt = paidReceipt(1, 7, 50.0);
        when(paymentGateway.refund(anyInt(), anyDouble())).thenThrow(new IllegalStateException("adapter bug"));

        assertThrows(RefundOutcomeUnknownException.class, this::refundCancelledEvent);

        assertTrue(allLinesPending(receipt));
    }

    @Test
    void GivenGatewayUnreachable_WhenRefundCancelledEvent_ThenTheClaimIsReleasedAndTheRunStops() {
        OrderReceipt first = paidReceipt(1, 7, 50.0);
        OrderReceipt second = paidReceipt(2, 8, 50.0);
        when(paymentGateway.refund(eq(chargeOf(1)), anyDouble()))
                .thenThrow(new RefundGatewayUnreachableException(chargeOf(1), "refused", new RuntimeException()));

        assertThrows(RefundGatewayUnreachableException.class, this::refundCancelledEvent);

        assertTrue(noLineTouched(first), "nothing reached the gateway, so the claim is released for a retry");
        assertTrue(noLineTouched(second));
        verify(paymentGateway, never()).refund(eq(chargeOf(2)), anyDouble());
    }

    // ---- tickets --------------------------------------------------------

    @Test
    void GivenIssuedTicket_WhenRefundCancelledEvent_ThenTicketRefunded() {
        paidReceipt(1, 7, 50.0);
        tickets.get(0).markIssued("BARCODE123");

        refundCancelledEvent();

        assertEquals(TicketStatus.REFUNDED, tickets.get(0).getStatus());
    }

    @Test
    void GivenUnsoldTicket_WhenRefundCancelledEvent_ThenTicketVoided() {
        Ticket available = new Ticket(EVENT_ID, ZONE_ID, 99, "Z9", 50.0, 99, "BC99");
        available.release(); // -> AVAILABLE
        tickets.add(available);

        refundCancelledEvent();

        assertEquals(TicketStatus.VOIDED, available.getStatus());
    }

    // ---- notices --------------------------------------------------------

    @Test
    void GivenTwoReceiptsOfOneHolder_WhenRefundCancelledEvent_ThenTheHolderIsNotifiedOnce() {
        paidReceipt(1, 7, 50.0);
        paidReceipt(2, 7, 30.0);

        refundCancelledEvent();

        verify(eventPublisher, times(1)).publishEvent(new EventCancelledNotice(7, EVENT_ID, EVENT_NAME));
    }

    @Test
    void GivenHolderAlreadyRefunded_WhenRefundCancelledEventAgain_ThenTheHolderIsNotNotifiedAgain() {
        paidReceipt(1, 7, 50.0);
        refundCancelledEvent();

        refundCancelledEvent();

        verify(eventPublisher, times(1)).publishEvent(any(EventCancelledNotice.class));
    }
}
