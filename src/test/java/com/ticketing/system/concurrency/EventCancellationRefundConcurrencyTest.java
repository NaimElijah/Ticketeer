package com.ticketing.system.concurrency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.ticketing.system.catalog.domain.event.EventCancelledEvent;
import com.ticketing.system.sales.adapter.out.persistence.SpringDataOrderReceiptRepository;
import com.ticketing.system.sales.adapter.out.persistence.SpringDataTicketRepository;
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
import com.ticketing.system.shared.exception.RefundFailedException;

/**
 * Event-cancellation refunds are resumable and exactly-once (#20, ADR-001), proved on the real JPA
 * stack where every claim and record step commits in its own short transaction.
 *
 * <p>The flow is driven through its public boundary: publishing catalog's {@link EventCancelledEvent},
 * exactly as {@code EventManagementService.cancelEventAndRefund} does, including when an owner
 * re-invokes cancel on an already-cancelled event. The gateway is mocked so every successful refund
 * can be counted per original charge.
 */
@SpringBootTest
@ActiveProfiles("test")
class EventCancellationRefundConcurrencyTest {

    private static final int EVENT_ID = 900;
    private static final int[] RECEIPT_IDS = {71, 72, 73};
    private static final int BUYER_ID = 42;
    private static final int CHARGE_BASE = 6000;
    private static final double PRICE = 80.0;
    private static final int CONCURRENT_RUNS = 6;
    private static final LocalDateTime WHEN = LocalDateTime.of(2026, 6, 1, 12, 0);

    @Autowired private ApplicationEventPublisher publisher;
    @Autowired private OrderReceiptRepository orderReceiptRepository;
    @Autowired private TicketRepository ticketRepository;
    @Autowired private SpringDataOrderReceiptRepository receiptData;
    @Autowired private SpringDataTicketRepository ticketData;
    @Autowired private EventCancellationRefundService refundService;
    @Autowired private PlatformTransactionManager transactionManager;

    @MockitoBean
    private PaymentGateway paymentGateway;

    /** Successful refunds per original charge id: the exactly-once assertion counts these. */
    private final Map<Integer, AtomicInteger> refundsByCharge = new ConcurrentHashMap<>();
    private final AtomicInteger gatewayCalls = new AtomicInteger();
    /** 1-based gateway call that fails, or 0 for none. */
    private volatile int failingCall;

    @BeforeEach
    void setUp() {
        receiptData.deleteAll();
        ticketData.deleteAll();
        refundsByCharge.clear();
        gatewayCalls.set(0);
        failingCall = 0;

        when(paymentGateway.getId()).thenReturn("test-gateway");
        when(paymentGateway.refund(anyInt(), anyDouble())).thenAnswer(invocation -> {
            int chargeId = invocation.getArgument(0);
            double amount = invocation.getArgument(1);
            if (gatewayCalls.incrementAndGet() == failingCall) {
                throw new RefundFailedException(chargeId, "gateway unavailable");
            }
            refundsByCharge.computeIfAbsent(chargeId, id -> new AtomicInteger()).incrementAndGet();
            return new RefundResultDTO("refund-" + chargeId, String.valueOf(chargeId), amount, LocalDateTime.now(),
                    List.of(), List.of());
        });

        for (int receiptId : RECEIPT_IDS) {
            int ticketId = ticketRepository.nextId();
            ticketRepository.save(new Ticket(EVENT_ID, 1, receiptId, "A" + receiptId, PRICE, ticketId, "BC-" + ticketId));
            ReceiptLine line = new ReceiptLine(ticketId, PRICE, EVENT_ID, 1, "A" + receiptId, WHEN);
            TransactionRecord charge = TransactionRecord.paymentCharge(CHARGE_BASE + receiptId, "test-gateway", PRICE, "ILS", WHEN);
            orderReceiptRepository.save(OrderReceipt.forMember(receiptId, BUYER_ID, PRICE, List.of(line), List.of(charge)));
        }
    }

    @Test
    void GivenGatewayFailsOnTheSecondRefund_WhenCancellationIsReplayed_ThenEveryReceiptIsRefundedExactlyOnce() {
        failingCall = 2;

        assertThrows(RefundFailedException.class, this::publishCancellation);
        publishCancellation();

        assertEachReceiptRefundedExactlyOnce();
    }

    @Test
    void GivenConcurrentReplays_WhenCancellationRunsInParallel_ThenEachReceiptIsRefundedOnce() throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_RUNS);
        CountDownLatch armed = new CountDownLatch(CONCURRENT_RUNS);
        CountDownLatch fire = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(CONCURRENT_RUNS);
        AtomicInteger reportedStillPending = new AtomicInteger();
        ConcurrentLinkedQueue<Throwable> unexpected = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < CONCURRENT_RUNS; i++) {
            pool.submit(() -> {
                try {
                    armed.countDown();
                    fire.await(); // release every run at once to maximise the race
                    publishCancellation();
                } catch (RefundFailedException stillPending) {
                    reportedStillPending.incrementAndGet(); // skipped a receipt another run had claimed
                } catch (Throwable t) {
                    unexpected.add(t);
                } finally {
                    finished.countDown();
                }
            });
        }

        assertTrue(armed.await(5, TimeUnit.SECONDS), "workers did not arm in time");
        fire.countDown();
        assertTrue(finished.await(30, TimeUnit.SECONDS), "cancellation runs did not finish in time");
        pool.shutdownNow();

        assertTrue(unexpected.isEmpty(), "a run may only report refunds still pending, got: " + unexpected);
        assertTrue(reportedStillPending.get() < CONCURRENT_RUNS, "at least one run completes cleanly");
        publishCancellation(); // with every run finished, nothing is pending any more
        assertEachReceiptRefundedExactlyOnce();
    }

    // Review finding 5 on #129: even when a caller wraps the run in its own transaction, each claim must
    // commit before the gateway call, or a concurrent run could claim the same lines and V3-TX-02 breaks.
    @Test
    void GivenACallerTransaction_WhenRefundCancelledEvent_ThenEachClaimIsCommittedBeforeTheGatewayCall() {
        List<LineRefundState> committedStatesDuringGatewayCalls = new CopyOnWriteArrayList<>();
        when(paymentGateway.refund(anyInt(), anyDouble())).thenAnswer(invocation -> {
            int chargeId = invocation.getArgument(0);
            committedStatesDuringGatewayCalls.add(committedRefundStateOf(chargeId - CHARGE_BASE));
            return new RefundResultDTO("refund-" + chargeId, String.valueOf(chargeId), invocation.getArgument(1),
                    LocalDateTime.now(), List.of(), List.of());
        });

        new TransactionTemplate(transactionManager).executeWithoutResult(
                status -> refundService.refundCancelledEvent(EVENT_ID, "Gala"));

        assertEquals(List.of(LineRefundState.PENDING, LineRefundState.PENDING, LineRefundState.PENDING),
                committedStatesDuringGatewayCalls);
    }

    /** Reads the receipt's line state in a separate transaction, so it sees only committed data. */
    private LineRefundState committedRefundStateOf(int receiptId) {
        TransactionTemplate separate = new TransactionTemplate(transactionManager);
        separate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return separate.execute(status -> orderReceiptRepository.findByOrderReceiptId(receiptId).orElseThrow()
                .getReceiptLines().get(0).getRefundState());
    }

    private void publishCancellation() {
        publisher.publishEvent(new EventCancelledEvent(EVENT_ID, "Gala"));
    }

    private void assertEachReceiptRefundedExactlyOnce() {
        for (int receiptId : RECEIPT_IDS) {
            int chargeId = CHARGE_BASE + receiptId;
            assertEquals(1, refundsByCharge.getOrDefault(chargeId, new AtomicInteger()).get(),
                    "charge " + chargeId + " must be refunded exactly once");
            OrderReceipt receipt = orderReceiptRepository.findByOrderReceiptId(receiptId).orElseThrow();
            assertTrue(receipt.getReceiptLines().stream().allMatch(ReceiptLine::isRefunded),
                    "every line of receipt " + receiptId + " is recorded as refunded");
        }
        for (Ticket ticket : ticketRepository.findByEventId(EVENT_ID)) {
            assertEquals(TicketStatus.REFUNDED, ticket.getStatus(), "ticket " + ticket.getId());
        }
    }
}
