package com.ticketing.system.unit.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import com.ticketing.system.shared.dto.PaymentRequestDTO;
import com.ticketing.system.shared.dto.PaymentResultDTO;
import com.ticketing.system.shared.dto.RefundResultDTO;
import com.ticketing.system.sales.application.port.out.PaymentGateway;
import com.ticketing.system.identity.application.service.AuthenticationService;
import com.ticketing.system.sales.application.service.RefundService;
import com.ticketing.system.sales.application.port.out.TicketRepository;
import com.ticketing.system.sales.domain.Ticket;
import com.ticketing.system.sales.domain.TicketStatus;
import com.ticketing.system.catalog.domain.Event;
import com.ticketing.system.catalog.application.port.out.EventRepository;
import com.ticketing.system.shared.exception.BusinessRuleViolationException;
import com.ticketing.system.shared.exception.EntityNotFoundException;
import com.ticketing.system.shared.exception.InvalidTokenException;
import com.ticketing.system.shared.exception.RefundFailedException;
import com.ticketing.system.shared.exception.UnauthorizedActionException;
import com.ticketing.system.sales.application.port.out.OrderReceiptRepository;
import com.ticketing.system.sales.domain.OrderReceipt;
import com.ticketing.system.sales.domain.ReceiptLine;
import com.ticketing.system.sales.domain.TransactionRecord;
import com.ticketing.system.sales.adapter.out.persistence.MemoryOrderReceiptRepository;
import com.ticketing.system.testutil.TestTransactions;

@ExtendWith(MockitoExtension.class)
class RefundServiceTest {

    @Mock AuthenticationService authenticationService;
    @Mock OrderReceiptRepository orderReceiptRepository;
    @Mock TicketRepository ticketRepository;
    @Mock PaymentGateway paymentGateway;
    @Mock EventRepository eventRepository;
    @Mock Ticket ticket;
    @Mock Event event;

    RefundService service;

    static final String VALID_TOKEN   = "valid-token";
    static final String INVALID_TOKEN = "bad-token";
    static final int    USER_ID       = 42;
    static final int    OTHER_USER    = 99;
    static final int    ORDER_ID      = 7;
    static final int    EVENT_ID      = 10;
    static final int    ZONE_ID       = 3;
    static final int    PAYMENT_TX    = 555;
    static final double TOTAL         = 150.00;
    static final LocalDateTime WHEN   = LocalDateTime.of(2026, 6, 1, 12, 0);

    @BeforeEach
    void setUp() {
        service = new RefundService(authenticationService, orderReceiptRepository, ticketRepository,
                paymentGateway, eventRepository, TestTransactions.noOpManager());
    }

    private static OrderReceipt memberReceiptWithCharge(int userId) {
        ReceiptLine line = new ReceiptLine(101, TOTAL, 2, 1, "15", WHEN);
        TransactionRecord charge = TransactionRecord.paymentCharge(PAYMENT_TX, "stub", TOTAL, "ILS", WHEN);
        return OrderReceipt.forMember(ORDER_ID, userId, TOTAL, List.of(line), List.of(charge));
    }

    private static RefundResultDTO gatewayResult() {
        return new RefundResultDTO("refund-1", String.valueOf(PAYMENT_TX), TOTAL, WHEN, List.of(), List.of());
    }

    @Test
    void givenOwnPaidOrder_whenRequestRefund_thenReceiptAndTicketsRefunded() {
        OrderReceipt receipt = memberReceiptWithCharge(USER_ID);
        RefundResultDTO result = gatewayResult();
        Mockito.when(authenticationService.validateToken(VALID_TOKEN)).thenReturn(true);
        Mockito.when(authenticationService.extractUserId(VALID_TOKEN)).thenReturn(USER_ID);
        Mockito.when(orderReceiptRepository.findByOrderReceiptId(ORDER_ID)).thenReturn(java.util.Optional.of(receipt));
        Mockito.when(paymentGateway.refund(PAYMENT_TX, TOTAL)).thenReturn(result);
        Mockito.when(paymentGateway.getId()).thenReturn("stub");
        Mockito.when(ticketRepository.findByOrderReceiptId(ORDER_ID)).thenReturn(List.of(ticket));
        Mockito.when(ticket.getStatus()).thenReturn(TicketStatus.PAID);

        RefundResultDTO returned = service.requestRefund(VALID_TOKEN, ORDER_ID, "Can't attend");

        assertThat(returned).isSameAs(result);
        assertThat(receipt.wasRefunded()).isTrue();
        Mockito.verify(paymentGateway).refund(PAYMENT_TX, TOTAL);
        Mockito.verify(ticket).markRefunded();
        Mockito.verify(ticketRepository).save(ticket);
        Mockito.verify(orderReceiptRepository).save(receipt);
    }

    @Test
    void givenSeatedPaidOrder_whenRequestRefund_thenSeatReturnedToStock() {
        OrderReceipt receipt = memberReceiptWithCharge(USER_ID);
        RefundResultDTO result = gatewayResult();
        Mockito.when(authenticationService.validateToken(VALID_TOKEN)).thenReturn(true);
        Mockito.when(authenticationService.extractUserId(VALID_TOKEN)).thenReturn(USER_ID);
        Mockito.when(orderReceiptRepository.findByOrderReceiptId(ORDER_ID)).thenReturn(java.util.Optional.of(receipt));
        Mockito.when(paymentGateway.refund(PAYMENT_TX, TOTAL)).thenReturn(result);
        Mockito.when(paymentGateway.getId()).thenReturn("stub");
        Mockito.when(ticketRepository.findByOrderReceiptId(ORDER_ID)).thenReturn(List.of(ticket));
        Mockito.when(ticket.getStatus()).thenReturn(TicketStatus.PAID);
        Mockito.when(ticket.getEventId()).thenReturn(EVENT_ID);
        Mockito.when(ticket.getZoneId()).thenReturn(ZONE_ID);
        Mockito.when(ticket.isSeatedTicket()).thenReturn(true);
        Mockito.when(ticket.getSeatNumber()).thenReturn("A9");
        Mockito.when(eventRepository.findById(EVENT_ID)).thenReturn(event);

        service.requestRefund(VALID_TOKEN, ORDER_ID, "Can't attend");

        Mockito.verify(ticket).markRefunded();
        // The refunded seat is returned to the event's stock, and the event is persisted...
        Mockito.verify(event).returnSoldToStock(Mockito.eq(ZONE_ID), Mockito.any());
        Mockito.verify(eventRepository).save(event);
        // ...under the buyer lock (MemoryEventRepository.save requires the caller to hold it).
        Mockito.verify(eventRepository).lockForBuyerOperation(EVENT_ID);
        Mockito.verify(eventRepository).unlockBuyerOperation(EVENT_ID);
    }

    @Test
    void givenInventoryReturnFails_whenRequestRefund_thenRefundStillSucceeds() {
        // Regression: a failure while returning inventory to stock (e.g. the event-lock guard, as in
        // issue's "Event must be locked before saving") must NOT propagate — the gateway refund and the
        // receipt/ticket flips have already committed, so the refund must still report success.
        OrderReceipt receipt = memberReceiptWithCharge(USER_ID);
        RefundResultDTO result = gatewayResult();
        Mockito.when(authenticationService.validateToken(VALID_TOKEN)).thenReturn(true);
        Mockito.when(authenticationService.extractUserId(VALID_TOKEN)).thenReturn(USER_ID);
        Mockito.when(orderReceiptRepository.findByOrderReceiptId(ORDER_ID)).thenReturn(java.util.Optional.of(receipt));
        Mockito.when(paymentGateway.refund(PAYMENT_TX, TOTAL)).thenReturn(result);
        Mockito.when(paymentGateway.getId()).thenReturn("stub");
        Mockito.when(ticketRepository.findByOrderReceiptId(ORDER_ID)).thenReturn(List.of(ticket));
        Mockito.when(ticket.getStatus()).thenReturn(TicketStatus.PAID);
        Mockito.when(ticket.getEventId()).thenReturn(EVENT_ID);
        Mockito.when(ticket.getZoneId()).thenReturn(ZONE_ID);
        Mockito.when(ticket.isSeatedTicket()).thenReturn(true);
        Mockito.when(ticket.getSeatNumber()).thenReturn("A9");
        Mockito.when(eventRepository.findById(EVENT_ID)).thenReturn(event);
        Mockito.doThrow(new IllegalStateException("Event " + EVENT_ID + " must be locked before saving"))
                .when(eventRepository).save(event);

        RefundResultDTO returned = service.requestRefund(VALID_TOKEN, ORDER_ID, "Can't attend");

        assertThat(returned).isSameAs(result);
        assertThat(receipt.wasRefunded()).isTrue();
        Mockito.verify(ticket).markRefunded();
        // Even though the save blew up, the lock was still released.
        Mockito.verify(eventRepository).unlockBuyerOperation(EVENT_ID);
    }

    @Test
    void givenInvalidToken_whenRequestRefund_thenInvalidTokenThrown() {
        Mockito.when(authenticationService.validateToken(INVALID_TOKEN)).thenReturn(false);

        assertThatThrownBy(() -> service.requestRefund(INVALID_TOKEN, ORDER_ID, "x"))
                .isInstanceOf(InvalidTokenException.class);

        Mockito.verify(paymentGateway, Mockito.never()).refund(Mockito.anyInt(), Mockito.anyDouble());
    }

    @Test
    void givenMissingOrder_whenRequestRefund_thenEntityNotFoundThrown() {
        Mockito.when(authenticationService.validateToken(VALID_TOKEN)).thenReturn(true);
        Mockito.when(authenticationService.extractUserId(VALID_TOKEN)).thenReturn(USER_ID);
        Mockito.when(orderReceiptRepository.findByOrderReceiptId(ORDER_ID)).thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> service.requestRefund(VALID_TOKEN, ORDER_ID, "x"))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void givenAnotherMembersOrder_whenRequestRefund_thenUnauthorizedThrown() {
        OrderReceipt receipt = memberReceiptWithCharge(OTHER_USER);
        Mockito.when(authenticationService.validateToken(VALID_TOKEN)).thenReturn(true);
        Mockito.when(authenticationService.extractUserId(VALID_TOKEN)).thenReturn(USER_ID);
        Mockito.when(orderReceiptRepository.findByOrderReceiptId(ORDER_ID)).thenReturn(java.util.Optional.of(receipt));

        assertThatThrownBy(() -> service.requestRefund(VALID_TOKEN, ORDER_ID, "x"))
                .isInstanceOf(UnauthorizedActionException.class);

        Mockito.verify(paymentGateway, Mockito.never()).refund(Mockito.anyInt(), Mockito.anyDouble());
    }

    @Test
    void givenAlreadyRefundedOrder_whenRequestRefund_thenNotEligibleThrown() {
        OrderReceipt receipt = memberReceiptWithCharge(USER_ID);
        receipt.markRefunded();
        Mockito.when(authenticationService.validateToken(VALID_TOKEN)).thenReturn(true);
        Mockito.when(authenticationService.extractUserId(VALID_TOKEN)).thenReturn(USER_ID);
        Mockito.when(orderReceiptRepository.findByOrderReceiptId(ORDER_ID)).thenReturn(java.util.Optional.of(receipt));

        assertThatThrownBy(() -> service.requestRefund(VALID_TOKEN, ORDER_ID, "x"))
                .isInstanceOf(BusinessRuleViolationException.class);

        Mockito.verify(paymentGateway, Mockito.never()).refund(Mockito.anyInt(), Mockito.anyDouble());
    }

    @Test
    void givenGatewayFailure_whenRequestRefund_thenRefundFailedPropagatesAndNoStateChange() {
        OrderReceipt receipt = memberReceiptWithCharge(USER_ID);
        Mockito.when(authenticationService.validateToken(VALID_TOKEN)).thenReturn(true);
        Mockito.when(authenticationService.extractUserId(VALID_TOKEN)).thenReturn(USER_ID);
        Mockito.when(orderReceiptRepository.findByOrderReceiptId(ORDER_ID)).thenReturn(java.util.Optional.of(receipt));
        Mockito.when(paymentGateway.refund(PAYMENT_TX, TOTAL))
                .thenThrow(new RefundFailedException(ORDER_ID, "gateway down"));

        assertThatThrownBy(() -> service.requestRefund(VALID_TOKEN, ORDER_ID, "x"))
                .isInstanceOf(RefundFailedException.class);

        assertThat(receipt.wasRefunded()).isFalse();
        Mockito.verify(orderReceiptRepository, Mockito.never()).save(Mockito.any());
    }

    // X1 regression: two concurrent refund requests for the same order must not both reach the gateway.
    // Uses the real MemoryOrderReceiptRepository so the per-receipt lock actually serializes the calls;
    // the loser must see the order already refunded and bail. Deterministic given the lock (one wins,
    // one throws BusinessRuleViolationException) regardless of thread timing.
    @Test
    void givenConcurrentRefundRequests_whenRequestRefund_thenGatewayRefundsExactlyOnce() throws Exception {
        OrderReceiptRepository realReceiptRepo = new MemoryOrderReceiptRepository();
        OrderReceipt receipt = memberReceiptWithCharge(USER_ID);
        realReceiptRepo.save(receipt); // id == ORDER_ID

        AtomicInteger refundCalls = new AtomicInteger();
        PaymentGateway countingGateway = new PaymentGateway() {
            @Override public String getId() { return "stub"; }
            @Override public boolean verifyConnection() { return true; }
            @Override public PaymentResultDTO charge(PaymentRequestDTO r) { throw new UnsupportedOperationException(); }
            @Override public RefundResultDTO refund(int txId, double amount) {
                refundCalls.incrementAndGet();
                return new RefundResultDTO("refund-" + txId, String.valueOf(txId), amount, WHEN, List.of(), List.of());
            }
        };

        AuthenticationService auth = Mockito.mock(AuthenticationService.class);
        Mockito.when(auth.validateToken(VALID_TOKEN)).thenReturn(true);
        Mockito.when(auth.extractUserId(VALID_TOKEN)).thenReturn(USER_ID);
        TicketRepository ticketRepo = Mockito.mock(TicketRepository.class);
        Mockito.when(ticketRepo.findByOrderReceiptId(ORDER_ID)).thenReturn(List.of());
        EventRepository eventRepo = Mockito.mock(EventRepository.class);

        RefundService concurrentService = new RefundService(auth, realReceiptRepo, ticketRepo, countingGateway, eventRepo, TestTransactions.noOpManager());

        int threads = 2;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger alreadyRefunded = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                barrier.await();
                try {
                    concurrentService.requestRefund(VALID_TOKEN, ORDER_ID, "x");
                    succeeded.incrementAndGet();
                } catch (BusinessRuleViolationException expected) {
                    alreadyRefunded.incrementAndGet();
                }
                return null;
            }));
        }
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdownNow();

        assertThat(refundCalls.get()).isEqualTo(1);
        assertThat(succeeded.get()).isEqualTo(1);
        assertThat(alreadyRefunded.get()).isEqualTo(1);
        assertThat(receipt.wasRefunded()).isTrue();
    }
}
