package com.ticketing.system.unit.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.SimpleTransactionStatus;

import com.ticketing.system.shared.dto.PaymentRequestDTO;
import com.ticketing.system.shared.dto.PaymentResultDTO;
import com.ticketing.system.shared.dto.RefundResultDTO;
import com.ticketing.system.sales.application.port.out.PaymentGateway;
import com.ticketing.system.identity.application.service.AuthenticationService;
import com.ticketing.system.sales.application.service.RefundService;
import com.ticketing.system.sales.application.port.out.TicketRepository;
import com.ticketing.system.sales.domain.Ticket;
import com.ticketing.system.sales.domain.TicketStatus;
import com.ticketing.system.catalog.application.port.in.InventoryCommandPort;
import com.ticketing.system.catalog.application.port.in.InventoryLineDTO;
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
import com.ticketing.system.testutil.LogCapture;
import com.ticketing.system.testutil.TestTransactions;

import ch.qos.logback.classic.Level;

@ExtendWith(MockitoExtension.class)
class RefundServiceTest {

    @Mock AuthenticationService authenticationService;
    @Mock OrderReceiptRepository orderReceiptRepository;
    @Mock TicketRepository ticketRepository;
    @Mock PaymentGateway paymentGateway;
    @Mock InventoryCommandPort inventoryPort;
    @Mock Ticket ticket;

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
                paymentGateway, inventoryPort, TestTransactions.noOpManager());
    }

    private static OrderReceipt memberReceiptWithCharge(int userId) {
        ReceiptLine line = new ReceiptLine(101, TOTAL, 2, 1, "15", WHEN);
        TransactionRecord charge = TransactionRecord.paymentCharge(PAYMENT_TX, "stub", TOTAL, "ILS", WHEN);
        return OrderReceipt.forMember(ORDER_ID, userId, TOTAL, List.of(line), LocalDateTime.now(), List.of(charge));
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
        assertThat(receipt.isFullyRefunded()).isTrue();
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

        service.requestRefund(VALID_TOKEN, ORDER_ID, "Can't attend");

        Mockito.verify(ticket).markRefunded();
        // The refunded seat is handed to the catalog inventory port as a flat line (event, zone, seat).
        // The port owns the buyer-lock / returnSoldToStock / save (now verified in InventoryServiceTest).
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<InventoryLineDTO>> linesCaptor = ArgumentCaptor.forClass(List.class);
        Mockito.verify(inventoryPort).returnSoldToStock(linesCaptor.capture());
        assertThat(linesCaptor.getValue())
                .containsExactly(new InventoryLineDTO(EVENT_ID, ZONE_ID, "A9"));
    }

    @Test
    void givenInventoryReturnFails_whenRequestRefund_thenRefundStillSucceeds() {
        // Regression: a failure while returning inventory to stock must NOT propagate — the gateway
        // refund and the receipt/ticket flips have already committed, so the refund must still report
        // success. The inventory return is now owned by the catalog port; RefundService guards the call
        // at the sales boundary so even a throwing port cannot turn a committed refund into a failure.
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
        Mockito.doThrow(new IllegalStateException("Event " + EVENT_ID + " must be locked before saving"))
                .when(inventoryPort).returnSoldToStock(Mockito.anyList());

        RefundResultDTO returned = service.requestRefund(VALID_TOKEN, ORDER_ID, "Can't attend");

        assertThat(returned).isSameAs(result);
        assertThat(receipt.isFullyRefunded()).isTrue();
        Mockito.verify(ticket).markRefunded();
        // The port was invoked (and its failure swallowed) after the refund committed.
        Mockito.verify(inventoryPort).returnSoldToStock(Mockito.anyList());
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

    // #20: an event-cancellation refund has claimed the order's lines (PENDING) and is calling the
    // gateway; a whole-order member refund must be refused before it charges the money back again.
    @Test
    void GivenLinesClaimedByAnEventCancellation_WhenRequestRefund_ThenNotEligibleAndGatewayNotCalled() {
        OrderReceipt receipt = memberReceiptWithCharge(USER_ID);
        receipt.claimRefundForEvent(2);
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

        assertThat(receipt.isFullyRefunded()).isFalse();
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
        InventoryCommandPort inventoryPortMock = Mockito.mock(InventoryCommandPort.class);

        RefundService concurrentService = new RefundService(auth, realReceiptRepo, ticketRepo, countingGateway, inventoryPortMock, TestTransactions.noOpManager());

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
        assertThat(receipt.isFullyRefunded()).isTrue();
    }

    // ---- Defect 4: the ticket flips commit together, after the refund has committed (#187) ----
    // The gateway has paid the money back by the time the refund transaction commits, so the flips must not
    // join it: rolling it back over a failed flip would undo a refund the gateway made, and a retry would
    // refund again. They run in one transaction of their own once it has committed, all or nothing. If that
    // transaction fails, the refund still stands: an ERROR names the order, the tickets stay valid, and no
    // stock is returned, since freeing seats whose tickets are still valid could sell a seat twice.

    @Mock Ticket secondTicket;

    @Test
    void GivenOwnPaidOrder_WhenRequestRefund_ThenTheTicketFlipsAreSavedInOneTransactionBegunAfterTheRefundCommitted() {
        RecordingTransactionManager transactions = new RecordingTransactionManager();
        RefundService transactionalService = new RefundService(authenticationService, orderReceiptRepository,
                ticketRepository, paymentGateway, inventoryPort, transactions);
        givenOwnPaidOrderOfTwoTicketsRefundedByTheGateway();
        transactions.recordEach(orderReceiptRepository, ticketRepository, inventoryPort);

        transactionalService.requestRefund(VALID_TOKEN, ORDER_ID, "Can't attend");

        assertThat(transactions.events())
                .as("the receipt is refunded in the first transaction; both ticket flips share a second one, "
                        + "begun after the first committed; the stock return follows once they have committed")
                .containsExactly(
                        "begin 1", "receipt saved in 1", "commit 1",
                        "begin 2", "ticket saved in 2", "ticket saved in 2", "commit 2",
                        "stock returned in 0");
    }

    @Test
    void GivenTicketSaveFails_WhenRequestRefund_ThenTheRefundStandsAnErrorNamesTheOrderAndNoStockIsReturned() {
        RecordingTransactionManager transactions = new RecordingTransactionManager();
        RefundService transactionalService = new RefundService(authenticationService, orderReceiptRepository,
                ticketRepository, paymentGateway, inventoryPort, transactions);
        OrderReceipt receipt = givenOwnPaidOrderOfTwoTicketsRefundedByTheGateway();
        DataAccessResourceFailureException storeDown = new DataAccessResourceFailureException("database unreachable");
        Mockito.doReturn(true).when(ticketRepository).save(ticket);
        Mockito.doThrow(storeDown).when(ticketRepository).save(secondTicket);

        try (LogCapture logs = LogCapture.of(RefundService.class)) {
            RefundResultDTO returned = transactionalService.requestRefund(VALID_TOKEN, ORDER_ID, "x");

            assertThat(returned).as("the money is back, so the member is told the refund is done")
                    .isEqualTo(gatewayResult());
            assertThat(receipt.isFullyRefunded()).as("the receipt stays refunded").isTrue();
            Mockito.verify(orderReceiptRepository).save(receipt);
            assertThat(transactions.commits()).as("the refund transaction committed").isEqualTo(1);
            assertThat(transactions.rollbacks()).as("the ticket flips roll back together").isEqualTo(1);
            assertThat(logs.messagesAt(Level.ERROR))
                    .as("an operator must learn which refunded order kept valid tickets")
                    .anyMatch(message -> message.contains("order " + ORDER_ID));
            assertThat(logs.throwableClassNamesAt(Level.ERROR)).contains(storeDown.getClass().getName());
            Mockito.verify(inventoryPort, Mockito.never()).returnSoldToStock(Mockito.anyList());
        }
    }

    // ---- G3: the flips always get a transaction of their own (#187, ADR-018) ----
    // Joining a caller's transaction, a failed flip would mark it rollback-only and undo the receipt's REFUNDED
    // after the money is back, and a retry would refund again.

    // The refund's own transaction too: joined to a caller's, that caller's later rollback would undo the
    // receipt's REFUNDED after the gateway paid the money back.
    @Test
    void GivenOwnPaidOrder_WhenRequestRefund_ThenTheRefundAndTheTicketFlipsEachAskForANewTransaction() {
        RecordingTransactionManager transactions = new RecordingTransactionManager();
        RefundService transactionalService = new RefundService(authenticationService, orderReceiptRepository,
                ticketRepository, paymentGateway, inventoryPort, transactions);
        givenOwnPaidOrderOfTwoTicketsRefundedByTheGateway();

        transactionalService.requestRefund(VALID_TOKEN, ORDER_ID, "Can't attend");

        assertThat(transactions.propagationOf(1))
                .as("the refund, the first transaction, must never join a caller's transaction")
                .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        assertThat(transactions.propagationOf(2))
                .as("the ticket flips, the second transaction, must never join a caller's transaction")
                .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // The flips can fail at their commit too, after every save went through: the refund still stands.
    @Test
    void GivenTheTicketFlipsCommitFails_WhenRequestRefund_ThenTheRefundStandsAnErrorNamesTheOrderAndNoStockIsReturned() {
        RecordingTransactionManager transactions = new RecordingTransactionManager();
        TransactionSystemException commitFailed = new TransactionSystemException("commit failed");
        transactions.failCommitOf(2, commitFailed);
        RefundService transactionalService = new RefundService(authenticationService, orderReceiptRepository,
                ticketRepository, paymentGateway, inventoryPort, transactions);
        OrderReceipt receipt = givenOwnPaidOrderOfTwoTicketsRefundedByTheGateway();

        try (LogCapture logs = LogCapture.of(RefundService.class)) {
            RefundResultDTO returned = transactionalService.requestRefund(VALID_TOKEN, ORDER_ID, "x");

            assertThat(returned).as("the money is back, so the member is told the refund is done")
                    .isEqualTo(gatewayResult());
            assertThat(receipt.isFullyRefunded()).as("the receipt stays refunded").isTrue();
            assertThat(logs.messagesAt(Level.ERROR))
                    .as("an operator must learn which refunded order kept valid tickets")
                    .anyMatch(message -> message.contains("order " + ORDER_ID));
            assertThat(logs.throwableClassNamesAt(Level.ERROR)).contains(commitFailed.getClass().getName());
            Mockito.verify(inventoryPort, Mockito.never()).returnSoldToStock(Mockito.anyList());
        }
    }

    // The member's own PAID order with two PAID standing tickets, which the gateway refunds.
    private OrderReceipt givenOwnPaidOrderOfTwoTicketsRefundedByTheGateway() {
        OrderReceipt receipt = memberReceiptWithCharge(USER_ID);
        Mockito.when(authenticationService.validateToken(VALID_TOKEN)).thenReturn(true);
        Mockito.when(authenticationService.extractUserId(VALID_TOKEN)).thenReturn(USER_ID);
        Mockito.when(orderReceiptRepository.findByOrderReceiptId(ORDER_ID)).thenReturn(java.util.Optional.of(receipt));
        Mockito.when(paymentGateway.refund(PAYMENT_TX, TOTAL)).thenReturn(gatewayResult());
        Mockito.when(paymentGateway.getId()).thenReturn("stub");
        Mockito.when(ticketRepository.findByOrderReceiptId(ORDER_ID)).thenReturn(List.of(ticket, secondTicket));
        for (Ticket each : List.of(ticket, secondTicket)) {
            Mockito.lenient().when(each.getStatus()).thenReturn(TicketStatus.PAID);
            Mockito.lenient().when(each.getEventId()).thenReturn(EVENT_ID);
            Mockito.lenient().when(each.getZoneId()).thenReturn(ZONE_ID);
        }
        return receipt;
    }

    /**
     * Records, in order, each transaction begun, committed and rolled back, numbered from 1, the propagation
     * each asked for, and each receipt save, ticket save and stock return with the number of the transaction
     * it ran in (0: none). A test can make one transaction's commit fail.
     */
    private static final class RecordingTransactionManager implements PlatformTransactionManager {

        private static final int NO_TRANSACTION = 0;

        private final List<String> events = new ArrayList<>();
        private final Map<Integer, Integer> propagations = new HashMap<>();
        private final Map<Integer, RuntimeException> commitFailures = new HashMap<>();
        private int begun;
        private int current = NO_TRANSACTION;
        private int commits;
        private int rollbacks;

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            current = ++begun;
            propagations.put(current, definition.getPropagationBehavior());
            events.add("begin " + current);
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            RuntimeException failure = commitFailures.get(current);
            if (failure != null) {
                events.add("commit failed " + current);
                current = NO_TRANSACTION;
                throw failure;
            }
            events.add("commit " + current);
            current = NO_TRANSACTION;
            commits++;
        }

        void failCommitOf(int transaction, RuntimeException failure) {
            commitFailures.put(transaction, failure);
        }

        int propagationOf(int transaction) {
            return propagations.get(transaction);
        }

        @Override
        public void rollback(TransactionStatus status) {
            events.add("rollback " + current);
            current = NO_TRANSACTION;
            rollbacks++;
        }

        // Each call is recorded, then left to do nothing; a stub set up later still overrides it.
        void recordEach(OrderReceiptRepository receipts, TicketRepository tickets, InventoryCommandPort inventory) {
            Mockito.doAnswer(invocation -> events.add("receipt saved in " + current)).when(receipts).save(Mockito.any());
            Mockito.doAnswer(invocation -> events.add("ticket saved in " + current)).when(tickets).save(Mockito.any());
            Mockito.doAnswer(invocation -> events.add("stock returned in " + current))
                    .when(inventory).returnSoldToStock(Mockito.anyList());
        }

        List<String> events() {
            return List.copyOf(events);
        }

        int commits() {
            return commits;
        }

        int rollbacks() {
            return rollbacks;
        }
    }
}
