package com.ticketing.system.concurrency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
    // How long a worker waits for the start signal: one that never gets it ends instead of waiting forever.
    private static final int FIRE_TIMEOUT_SECONDS = 10;
    // Bounds every wait of the held-claim test, so a run that never arrives fails it instead of hanging it.
    private static final long AWAIT_SECONDS = 20;
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
            return new RefundResultDTO("refund-" + chargeId, String.valueOf(chargeId), amount, WHEN,
                    List.of(), List.of());
        });

        for (int receiptId : RECEIPT_IDS) {
            int ticketId = ticketRepository.nextId();
            ticketRepository.save(new Ticket(EVENT_ID, 1, receiptId, "A" + receiptId, PRICE, ticketId, "BC-" + ticketId));
            ReceiptLine line = new ReceiptLine(ticketId, PRICE, EVENT_ID, 1, "A" + receiptId, WHEN);
            TransactionRecord charge = TransactionRecord.paymentCharge(CHARGE_BASE + receiptId, "test-gateway", PRICE, "ILS", WHEN);
            orderReceiptRepository.save(OrderReceipt.forMember(receiptId, BUYER_ID, PRICE, List.of(line), WHEN, List.of(charge)));
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

        try {
            for (int i = 0; i < CONCURRENT_RUNS; i++) {
                pool.submit(() -> {
                    try {
                        armed.countDown();
                        // release every run at once to maximise the race
                        if (!fire.await(FIRE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("the race was never started");
                        }
                        publishCancellation();
                    } catch (RefundFailedException refused) {
                        // A run that skipped a receipt another run had claimed reports it still pending.
                        // The gateway here never declines, never fails and always answers validly, so a
                        // decline, an unknown outcome or an unreachable gateway is not the lost race.
                        if (isOnlyStillPending(refused)) {
                            reportedStillPending.incrementAndGet();
                        } else {
                            unexpected.add(refused);
                        }
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
        } finally {
            // Interrupts the workers even when an assertion above failed. One blocked where an interrupt does not
            // reach, such as a database call, can still outlast the test.
            pool.shutdownNow();
        }

        assertTrue(unexpected.isEmpty(), "a run may only report refunds still pending, got: " + unexpected);
        assertTrue(reportedStillPending.get() < CONCURRENT_RUNS, "at least one run completes cleanly");
        publishCancellation(); // with every run finished, nothing is pending any more
        assertEachReceiptRefundedExactlyOnce();
    }

    // The replays above may run one after another, each finding the others' receipts already refunded. Here
    // the second run starts while the first is in the gateway with the first receipt's claim committed.
    @Test
    void GivenARunHoldingAClaimInTheGateway_WhenASecondRunStarts_ThenItSkipsTheReceiptAndReportsItPending()
            throws Exception {
        int heldReceipt = RECEIPT_IDS[0];
        int heldCharge = CHARGE_BASE + heldReceipt;
        CountDownLatch firstInGateway = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        holdTheFirstRefundOf(heldCharge, firstInGateway, releaseFirst);
        ExecutorService firstRunner = Executors.newSingleThreadExecutor();
        try {
            Future<?> firstRun = firstRunner.submit(this::publishCancellation);
            awaitOrFail(firstInGateway, "arrangement: the first run reaches the gateway with receipt " + heldReceipt);

            Throwable secondRunFailure = failureOf(() -> {
                publishCancellation();
                return null;
            });
            int heldChargeRefundsByTheSecondRun = refundsByCharge.get(heldCharge).get() - 1;
            releaseFirst.countDown();
            Throwable firstRunFailure = failureOf(() -> firstRun.get(AWAIT_SECONDS, TimeUnit.SECONDS));

            RefundFailedException reported = assertInstanceOf(RefundFailedException.class, secondRunFailure,
                    "the second run must not report the event refunded while a receipt's refund is in flight");
            assertTrue(isOnlyStillPending(reported)
                    && reported.getMessage().contains("still pending: [" + heldReceipt + "]"),
                    "the second run reports only the held receipt, as still pending: " + reported.getMessage());
            assertEquals(0, heldChargeRefundsByTheSecondRun, "the second run skips the claimed receipt");
            assertNull(firstRunFailure, "the first run completes the refund it claimed");
            assertEachReceiptRefundedExactlyOnce();
        } finally {
            releaseFirst.countDown();
            firstRunner.shutdownNow();
        }
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
                    WHEN, List.of(), List.of());
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

    /**
     * Makes the gateway hold the first refund of {@code heldCharge} until {@code release} opens, signalling
     * {@code inGateway} once it is held. Every refund answers validly and is counted in {@link #refundsByCharge}
     * as it arrives.
     */
    private void holdTheFirstRefundOf(int heldCharge, CountDownLatch inGateway, CountDownLatch release) {
        AtomicBoolean holding = new AtomicBoolean(true);
        doAnswer(invocation -> {
            int chargeId = invocation.getArgument(0);
            refundsByCharge.computeIfAbsent(chargeId, id -> new AtomicInteger()).incrementAndGet();
            if (chargeId == heldCharge && holding.getAndSet(false)) {
                inGateway.countDown();
                awaitOrFail(release, "the test releases the held refund");
            }
            return new RefundResultDTO("refund-" + chargeId, String.valueOf(chargeId), invocation.getArgument(1),
                    WHEN, List.of(), List.of());
        }).when(paymentGateway).refund(anyInt(), anyDouble());
    }

    /** What the call threw, unwrapped from a Future's ExecutionException; {@code null} when it returned. */
    private static Throwable failureOf(Callable<?> call) {
        try {
            call.call();
            return null;
        } catch (ExecutionException e) {
            return e.getCause();
        } catch (Exception e) {
            return e;
        }
    }

    private static void awaitOrFail(CountDownLatch latch, String what) throws InterruptedException {
        if (!latch.await(AWAIT_SECONDS, TimeUnit.SECONDS)) {
            throw new IllegalStateException(what + ", but it never did");
        }
    }

    /** Whether a run's failure reports only receipts still pending under another run's claim, none declined. */
    private static boolean isOnlyStillPending(RefundFailedException failure) {
        return failure.getClass() == RefundFailedException.class
                && failure.getMessage().contains("(declined: [], still pending: [");
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
