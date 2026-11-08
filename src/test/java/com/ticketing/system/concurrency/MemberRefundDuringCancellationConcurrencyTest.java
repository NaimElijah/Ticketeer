package com.ticketing.system.concurrency;

import static com.ticketing.system.testutil.Races.awaitOrFail;
import static com.ticketing.system.testutil.Races.awaitOrFailWith;
import static com.ticketing.system.testutil.Races.failureOf;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.DoubleConsumer;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.ticketing.system.catalog.domain.event.EventCancelledEvent;
import com.ticketing.system.identity.adapter.out.security.JwtSessionManager;
import com.ticketing.system.sales.adapter.out.persistence.SpringDataOrderReceiptRepository;
import com.ticketing.system.sales.adapter.out.persistence.SpringDataTicketRepository;
import com.ticketing.system.sales.application.port.out.OrderReceiptRepository;
import com.ticketing.system.sales.application.port.out.PaymentGateway;
import com.ticketing.system.sales.application.port.out.TicketRepository;
import com.ticketing.system.sales.application.service.RefundService;
import com.ticketing.system.sales.domain.LineRefundState;
import com.ticketing.system.sales.domain.OrderReceipt;
import com.ticketing.system.sales.domain.ReceiptLine;
import com.ticketing.system.sales.domain.Ticket;
import com.ticketing.system.sales.domain.TicketStatus;
import com.ticketing.system.sales.domain.TransactionRecord;
import com.ticketing.system.shared.dto.RefundResultDTO;
import com.ticketing.system.shared.exception.BusinessRuleViolationException;

/**
 * A member's whole-order refund and an event-cancellation refund of the same receipt, at once, on the
 * real JPA stack (#20). Each path takes the receipt's row lock: the member refund holds it across the
 * gateway call (#410), the cancellation only while it claims the event's lines and while it records the
 * refund. Whichever path reaches the receipt first refunds it; the other must then refund nothing, so each
 * line's money goes back once and the receipt and its tickets agree on what was refunded.
 *
 * <p>The receipt holds one ticket of the cancelled event and one of another event, so the two outcomes
 * differ: the member's refund returns both lines, the cancellation only the cancelled event's. The gateway
 * is mocked so each refund it is asked for can be counted. Profile {@code test} without {@code memory}:
 * JPA repositories on H2. {@code PostgresMemberRefundDuringCancellationConcurrencyIT} runs it on PostgreSQL.
 */
@SpringBootTest
@ActiveProfiles("test")
class MemberRefundDuringCancellationConcurrencyTest {

    private static final int RECEIPT_ID = 81;
    private static final int BUYER_ID = 42;
    private static final int CANCELLED_EVENT_ID = 910;
    private static final int OTHER_EVENT_ID = 911;
    private static final int ZONE_ID = 1;
    private static final int PAYMENT_TX = 7100;
    private static final double CANCELLED_EVENT_PRICE = 80.0;
    private static final double OTHER_EVENT_PRICE = 30.0;
    private static final double TOTAL = CANCELLED_EVENT_PRICE + OTHER_EVENT_PRICE;
    private static final LocalDateTime WHEN = LocalDateTime.of(2026, 6, 1, 12, 0);
    // What RefundService tells a member whose receipt another refund has reached first.
    private static final String ALREADY_REFUNDED = "Order " + RECEIPT_ID + " has already been refunded";
    // How long a worker waits for the start signal: one that never gets it ends instead of waiting forever.
    private static final Duration FIRE_TIMEOUT = Duration.ofSeconds(10);
    // Bounds every other wait, so a broken lock fails a test instead of hanging it.
    private static final long AWAIT_SECONDS = 20;
    // Polled this often for the waiting session: H2 ends a lock wait after its lock timeout, so the held
    // refund is released as soon as the wait shows.
    private static final Duration LOCK_WAIT_POLL = Duration.ofMillis(10);

    @Autowired private RefundService refundService;
    @Autowired private ApplicationEventPublisher publisher;
    @Autowired private OrderReceiptRepository orderReceiptRepository;
    @Autowired private TicketRepository ticketRepository;
    @Autowired private SpringDataOrderReceiptRepository receiptData;
    @Autowired private SpringDataTicketRepository ticketData;
    @Autowired private JwtSessionManager jwtSessionManager;
    @Autowired private JdbcTemplate jdbc;

    @MockitoBean
    private PaymentGateway paymentGateway;

    /** The amount of every refund the gateway was asked for, in the order the calls arrived. */
    private final List<Double> gatewayRefunds = new CopyOnWriteArrayList<>();

    private String buyerToken;
    private int cancelledEventTicketId;
    private int otherEventTicketId;

    @BeforeEach
    void setUp() {
        receiptData.deleteAll();
        ticketData.deleteAll();
        gatewayRefunds.clear();

        when(paymentGateway.getId()).thenReturn("test-gateway");
        answerEveryRefund(amount -> { });

        cancelledEventTicketId = ticketRepository.nextId();
        ticketRepository.save(new Ticket(CANCELLED_EVENT_ID, ZONE_ID, RECEIPT_ID, "A1", CANCELLED_EVENT_PRICE,
                cancelledEventTicketId, "BC-" + cancelledEventTicketId));
        otherEventTicketId = ticketRepository.nextId();
        ticketRepository.save(new Ticket(OTHER_EVENT_ID, ZONE_ID, RECEIPT_ID, "B1", OTHER_EVENT_PRICE,
                otherEventTicketId, "BC-" + otherEventTicketId));
        List<ReceiptLine> lines = List.of(
                new ReceiptLine(cancelledEventTicketId, CANCELLED_EVENT_PRICE, CANCELLED_EVENT_ID, ZONE_ID, "A1", WHEN),
                new ReceiptLine(otherEventTicketId, OTHER_EVENT_PRICE, OTHER_EVENT_ID, ZONE_ID, "B1", WHEN));
        TransactionRecord charge = TransactionRecord.paymentCharge(PAYMENT_TX, "test-gateway", TOTAL, "ILS", WHEN);
        orderReceiptRepository.save(OrderReceipt.forMember(RECEIPT_ID, BUYER_ID, TOTAL, lines, WHEN, List.of(charge)));

        buyerToken = jwtSessionManager.generateToken(BUYER_ID, "buyer");
    }

    @Test
    void GivenAMemberRefundAndACancellationOfOneReceipt_WhenBothStartAtOnce_ThenEachLineIsRefundedOnce()
            throws Exception {
        CountDownLatch fire = new CountDownLatch(1);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<RefundResultDTO> memberRefund = threads.submit(() -> {
                awaitOrFailWith(fire, FIRE_TIMEOUT, "the race was never started");
                return refundService.requestRefund(buyerToken, RECEIPT_ID, "changed my mind");
            });
            Future<?> cancellation = threads.submit(() -> {
                awaitOrFailWith(fire, FIRE_TIMEOUT, "the race was never started");
                cancelEvent();
                return null;
            });
            fire.countDown();

            Throwable memberFailure = failureOf(() -> memberRefund.get(AWAIT_SECONDS, TimeUnit.SECONDS));
            Throwable cancellationFailure = failureOf(() -> cancellation.get(AWAIT_SECONDS, TimeUnit.SECONDS));

            assertNull(cancellationFailure, "the cancellation completes, whichever path came first");
            if (memberFailure == null) {
                assertMemberRefundedEverything();
            } else {
                BusinessRuleViolationException refused = assertInstanceOf(BusinessRuleViolationException.class,
                        memberFailure, "a member refund that comes second is refused, never failed otherwise");
                assertEquals(ALREADY_REFUNDED, refused.getMessage());
                assertCancellationRefundedItsLineOnly();
            }
        } finally {
            threads.shutdownNow();
        }
    }

    // The member's refund holds the receipt's row lock while the gateway refunds the whole order, so the
    // cancellation's claim must wait for it, and then find nothing left to refund.
    @Test
    void GivenAMemberRefundHeldInTheGateway_WhenTheEventIsCancelledMeanwhile_ThenTheCancellationWaitsAndRefundsNothingMore()
            throws Exception {
        CountDownLatch memberInGateway = new CountDownLatch(1);
        CountDownLatch releaseMember = new CountDownLatch(1);
        holdTheFirstGatewayRefund(memberInGateway, releaseMember);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<RefundResultDTO> memberRefund = threads.submit(
                    () -> refundService.requestRefund(buyerToken, RECEIPT_ID, "changed my mind"));
            awaitOrFail(memberInGateway, Duration.ofSeconds(AWAIT_SECONDS),
                    "arrangement: the member's refund reaches the gateway");
            Future<?> cancellation = threads.submit(() -> {
                cancelEvent();
                return null;
            });
            await().atMost(Duration.ofSeconds(AWAIT_SECONDS)).pollDelay(Duration.ZERO).pollInterval(LOCK_WAIT_POLL)
                    .until(() -> cancellation.isDone() || aSessionWaitsOnALock());
            boolean cancellationDoneWhileMemberHeld = cancellation.isDone();
            int gatewayRefundsWhileMemberHeld = gatewayRefunds.size();
            releaseMember.countDown();

            Throwable memberFailure = failureOf(() -> memberRefund.get(AWAIT_SECONDS, TimeUnit.SECONDS));
            Throwable cancellationFailure = failureOf(() -> cancellation.get(AWAIT_SECONDS, TimeUnit.SECONDS));

            assertFalse(cancellationDoneWhileMemberHeld, "while the member's refund is in the gateway, the "
                    + "cancellation must wait on the receipt's row lock, but it finished with: " + cancellationFailure);
            assertEquals(1, gatewayRefundsWhileMemberHeld,
                    "while the member's refund is in the gateway, the cancellation must not reach it");
            assertNull(memberFailure, "the member's refund succeeds");
            assertNull(cancellationFailure, "the cancellation finds the receipt refunded and completes");
            assertMemberRefundedEverything();
        } finally {
            releaseMember.countDown();
            threads.shutdownNow();
        }
    }

    // The cancellation's claim commits before its gateway call, so a member refund arriving meanwhile reads the
    // claimed line, and must be refused without reaching the gateway.
    @Test
    void GivenACancellationRefundHeldInTheGateway_WhenTheMemberRequestsARefundMeanwhile_ThenItIsRefusedAndNeverReachesTheGateway()
            throws Exception {
        CountDownLatch cancellationInGateway = new CountDownLatch(1);
        CountDownLatch releaseCancellation = new CountDownLatch(1);
        holdTheFirstGatewayRefund(cancellationInGateway, releaseCancellation);
        ExecutorService threads = Executors.newSingleThreadExecutor();
        try {
            Future<?> cancellation = threads.submit(() -> {
                cancelEvent();
                return null;
            });
            awaitOrFail(cancellationInGateway, Duration.ofSeconds(AWAIT_SECONDS),
                    "arrangement: the cancellation's refund reaches the gateway");

            Throwable memberFailure = failureOf(
                    () -> refundService.requestRefund(buyerToken, RECEIPT_ID, "changed my mind"));
            int gatewayRefundsWhileCancellationHeld = gatewayRefunds.size();
            releaseCancellation.countDown();
            Throwable cancellationFailure = failureOf(() -> cancellation.get(AWAIT_SECONDS, TimeUnit.SECONDS));

            BusinessRuleViolationException refused = assertInstanceOf(BusinessRuleViolationException.class,
                    memberFailure, "a member refund of a receipt the cancellation has claimed is refused");
            assertEquals(ALREADY_REFUNDED, refused.getMessage());
            assertEquals(1, gatewayRefundsWhileCancellationHeld, "the refused member refund must not reach the gateway");
            assertNull(cancellationFailure, "the cancellation records the refund it claimed");
            assertCancellationRefundedItsLineOnly();
        } finally {
            releaseCancellation.countDown();
            threads.shutdownNow();
        }
    }

    /** Whether some connection is waiting for a lock another holds, as H2 reports it. */
    protected boolean aSessionWaitsOnALock() {
        return count("select count(*) from information_schema.sessions where blocker_id is not null") > 0;
    }

    protected int count(String sql) {
        Integer count = jdbc.queryForObject(sql, Integer.class);
        return count == null ? 0 : count;
    }

    private void cancelEvent() {
        publisher.publishEvent(new EventCancelledEvent(CANCELLED_EVENT_ID, "Gala"));
    }

    /** The member's refund won: one gateway refund of the whole order, and every line and ticket refunded. */
    private void assertMemberRefundedEverything() {
        assertEquals(List.of(TOTAL), gatewayRefunds, "one gateway refund, of the whole order");
        assertEquals(Map.of(cancelledEventTicketId, LineRefundState.REFUNDED, otherEventTicketId, LineRefundState.REFUNDED),
                lineStates());
        assertEquals(Map.of(cancelledEventTicketId, TicketStatus.REFUNDED, otherEventTicketId, TicketStatus.REFUNDED),
                ticketStatuses());
    }

    /** The cancellation won: one gateway refund of the cancelled event's line, and only that line and ticket refunded. */
    private void assertCancellationRefundedItsLineOnly() {
        assertEquals(List.of(CANCELLED_EVENT_PRICE), gatewayRefunds, "one gateway refund, of the cancelled event's line");
        assertEquals(Map.of(cancelledEventTicketId, LineRefundState.REFUNDED, otherEventTicketId, LineRefundState.NONE),
                lineStates());
        assertEquals(Map.of(cancelledEventTicketId, TicketStatus.REFUNDED, otherEventTicketId, TicketStatus.PAID),
                ticketStatuses());
    }

    /** Each line's refund state, by ticket id, as committed. */
    private Map<Integer, LineRefundState> lineStates() {
        return orderReceiptRepository.findByOrderReceiptId(RECEIPT_ID).orElseThrow().getReceiptLines().stream()
                .collect(Collectors.toMap(ReceiptLine::getTicketId, ReceiptLine::getRefundState));
    }

    /** Each ticket's status, by ticket id, as committed. */
    private Map<Integer, TicketStatus> ticketStatuses() {
        return ticketRepository.findByOrderReceiptId(RECEIPT_ID).stream()
                .collect(Collectors.toMap(Ticket::getId, Ticket::getStatus));
    }

    /** Records every refund in {@link #gatewayRefunds} as it arrives, runs {@code whileInGateway}, and answers validly. */
    private void answerEveryRefund(DoubleConsumer whileInGateway) {
        doAnswer(invocation -> {
            int chargeId = invocation.getArgument(0);
            double amount = invocation.getArgument(1);
            gatewayRefunds.add(amount);
            whileInGateway.accept(amount);
            return new RefundResultDTO("refund-" + gatewayRefunds.size(), String.valueOf(chargeId), amount, WHEN,
                    List.of(), List.of());
        }).when(paymentGateway).refund(anyInt(), anyDouble());
    }

    /** Holds the first refund in the gateway until {@code release} opens, signalling {@code inGateway} once it is held. */
    private void holdTheFirstGatewayRefund(CountDownLatch inGateway, CountDownLatch release) {
        AtomicBoolean first = new AtomicBoolean(true);
        answerEveryRefund(amount -> {
            if (first.getAndSet(false)) {
                inGateway.countDown();
                try {
                    awaitOrFail(release, Duration.ofSeconds(AWAIT_SECONDS), "the test releases the held refund");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted while the refund was held", interrupted);
                }
            }
        });
    }
}
