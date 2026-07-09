package com.ticketing.system.unit.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.catalog.domain.event.EventCancelledEvent;
import com.ticketing.system.sales.adapter.in.event.EventCancellationRefundListener;
import com.ticketing.system.sales.application.port.out.OrderReceiptRepository;
import com.ticketing.system.sales.application.port.out.PaymentGateway;
import com.ticketing.system.sales.application.port.out.TicketRepository;
import com.ticketing.system.sales.domain.OrderReceipt;
import com.ticketing.system.sales.domain.ReceiptLine;
import com.ticketing.system.sales.domain.Ticket;
import com.ticketing.system.sales.domain.TicketStatus;
import com.ticketing.system.sales.domain.TransactionRecord;
import com.ticketing.system.shared.dto.RefundResultDTO;
import com.ticketing.system.shared.event.EventCancelledNotice;

import org.springframework.context.ApplicationEventPublisher;

/**
 * Unit tests for {@link EventCancellationRefundListener}. These cover the refund / ticket-void /
 * holder-notification BEHAVIOUR that used to live inside {@code EventManagementService.cancelEventAndRefund}
 * and moved to the sales context when catalog was severed from sales. Each test drives the listener
 * directly via {@code on(new EventCancelledEvent(...))} with mocked sales ports.
 */
class EventCancellationRefundListenerTest {

    // Mocked sales ports and the publisher the listener depends on.
    private OrderReceiptRepository orderReceiptRepository;
    private TicketRepository ticketRepository;
    private PaymentGateway paymentGateway;
    private ApplicationEventPublisher eventPublisher;
    private EventCancellationRefundListener listener;

    // Shared fixture ids matching the former EventManagementServiceTest cancel/refund cases.
    private static final int EVENT_ID = 10;
    private static final int ZONE_ID = 5;
    private static final int ORDER_RECEIPT_ID = 20;
    private static final int OWNER_ID = 1;

    /** Fresh mocks and a fresh listener per test. */
    @BeforeEach
    void setUp() {
        orderReceiptRepository = mock(OrderReceiptRepository.class);            // receipts port
        ticketRepository = mock(TicketRepository.class);                       // tickets port
        paymentGateway = mock(PaymentGateway.class);                          // refund gateway
        eventPublisher = mock(ApplicationEventPublisher.class);               // notification publisher
        listener = new EventCancellationRefundListener(
                orderReceiptRepository, ticketRepository, paymentGateway, eventPublisher);
    }

    /** Builds a member receipt with one line for EVENT_ID and a recorded payment charge to refund. */
    private OrderReceipt memberReceiptWithCharge() {
        ReceiptLine line = new ReceiptLine(1, 100.0, EVENT_ID, 1, "A1", LocalDateTime.now()); // one paid line
        OrderReceipt receipt = OrderReceipt.forMember(99, OWNER_ID, 100.0, List.of(line));    // owned by OWNER_ID
        receipt.addTransaction(
                TransactionRecord.paymentCharge(42, "test-gateway", 100.0, "ILS", LocalDateTime.now())); // original charge
        return receipt;
    }

    /** Stubs a successful gateway refund of the full receipt amount. */
    private void stubSuccessfulRefund() {
        when(paymentGateway.getId()).thenReturn("test-gateway");
        when(paymentGateway.refund(anyInt(), anyDouble())).thenReturn(
                new RefundResultDTO("refund-tx-1", "99", 100.0, LocalDateTime.now(), List.of(), List.of()));
    }

    // A member receipt with a valid charge is refunded end-to-end (receipt flagged refunded).
    @Test
    void GivenMemberReceipt_WhenOnEventCancelled_ThenReceiptMarkedRefunded() {
        OrderReceipt receipt = memberReceiptWithCharge();
        when(orderReceiptRepository.findByEventId(EVENT_ID)).thenReturn(List.of(receipt));
        stubSuccessfulRefund();
        when(ticketRepository.findByEventId(EVENT_ID)).thenReturn(List.of());

        listener.on(new EventCancelledEvent(EVENT_ID, "Concert"));

        assertTrue(receipt.wasRefunded());
    }

    // Each distinct member ticket holder receives an EventCancelledNotice.
    @Test
    void GivenMemberReceipt_WhenOnEventCancelled_ThenTicketHolderNotified() {
        OrderReceipt receipt = memberReceiptWithCharge();
        when(orderReceiptRepository.findByEventId(EVENT_ID)).thenReturn(List.of(receipt));
        stubSuccessfulRefund();
        when(ticketRepository.findByEventId(EVENT_ID)).thenReturn(List.of());

        listener.on(new EventCancelledEvent(EVENT_ID, "Concert"));

        verify(eventPublisher).publishEvent(new EventCancelledNotice(OWNER_ID, EVENT_ID, "Concert"));
    }

    // A PAID ticket is flipped to REFUNDED.
    @Test
    void GivenPaidTicket_WhenOnEventCancelled_ThenTicketRefunded() {
        when(orderReceiptRepository.findByEventId(EVENT_ID)).thenReturn(List.of());
        Ticket paidTicket = new Ticket(EVENT_ID, ZONE_ID, ORDER_RECEIPT_ID, null, 100.0, 1, "BARCODE123"); // defaults to PAID
        when(ticketRepository.findByEventId(EVENT_ID)).thenReturn(List.of(paidTicket));

        listener.on(new EventCancelledEvent(EVENT_ID, "Concert"));

        assertEquals(TicketStatus.REFUNDED, paidTicket.getStatus());
    }

    // An ISSUED ticket is flipped to REFUNDED.
    @Test
    void GivenIssuedTicket_WhenOnEventCancelled_ThenTicketRefunded() {
        when(orderReceiptRepository.findByEventId(EVENT_ID)).thenReturn(List.of());
        Ticket issuedTicket = new Ticket(EVENT_ID, ZONE_ID, ORDER_RECEIPT_ID, null, 100.0, 1, "BARCODE123");
        issuedTicket.markIssued("BARCODE123");
        when(ticketRepository.findByEventId(EVENT_ID)).thenReturn(List.of(issuedTicket));

        listener.on(new EventCancelledEvent(EVENT_ID, "Concert"));

        assertEquals(TicketStatus.REFUNDED, issuedTicket.getStatus());
    }

    // A non-PAID/ISSUED (here AVAILABLE) ticket is VOIDED rather than refunded.
    @Test
    void GivenAvailableTicket_WhenOnEventCancelled_ThenTicketVoided() {
        when(orderReceiptRepository.findByEventId(EVENT_ID)).thenReturn(List.of());
        Ticket availableTicket = new Ticket(EVENT_ID, ZONE_ID, ORDER_RECEIPT_ID, null, 100.0, 1, "BARCODE123");
        availableTicket.release(); // -> AVAILABLE
        when(ticketRepository.findByEventId(EVENT_ID)).thenReturn(List.of(availableTicket));

        listener.on(new EventCancelledEvent(EVENT_ID, "Concert"));

        assertEquals(TicketStatus.VOIDED, availableTicket.getStatus());
    }
}
