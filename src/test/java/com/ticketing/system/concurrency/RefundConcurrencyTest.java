package com.ticketing.system.concurrency;

import static com.ticketing.system.testutil.Races.awaitOrFail;
import static com.ticketing.system.testutil.Races.failureOf;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.ticketing.system.shared.dto.RefundResultDTO;
import com.ticketing.system.sales.application.port.out.PaymentGateway;
import com.ticketing.system.sales.application.service.RefundService;
import com.ticketing.system.shared.exception.BusinessRuleViolationException;
import com.ticketing.system.sales.application.port.out.OrderReceiptRepository;
import com.ticketing.system.sales.domain.OrderReceipt;
import com.ticketing.system.sales.domain.ReceiptLine;
import com.ticketing.system.sales.domain.TransactionRecord;
import com.ticketing.system.sales.adapter.out.persistence.SpringDataOrderReceiptRepository;
import com.ticketing.system.identity.adapter.out.security.JwtSessionManager;

/**
 * No-double-refund concurrency proof (#410), run on the real JPA stack.
 *
 * <p>Under JPA the receipt's {@code lockForUpdate} used to be a no-op, so two concurrent refund
 * requests for the same receipt could both pass the eligibility check and both call
 * {@code paymentGateway.refund()} — refunding the buyer twice ({@code @Version} only fails the second
 * SAVE, <em>after</em> the gateway was already hit). One test fires N refunds at the same receipt
 * simultaneously and asserts the gateway is called <strong>exactly once</strong>. Its gateway answers at
 * once, so its threads may well run one after another, and it alone does not show the lock. The other
 * holds the first refund inside the gateway and shows a second request for the same receipt waiting on
 * the real {@code SELECT … FOR UPDATE} row lock, never reaching the gateway, then bailing as already
 * refunded once the first commits.
 *
 * <p>Profile {@code test} without {@code memory}: JPA repositories + H2. The gateway is mocked so {@code refund()} calls
 * can be counted; the platform-init runner that would probe it is {@code @Profile("!test")}, so it
 * stays off and the unstubbed mock is harmless at startup. {@code PostgresRefundConcurrencyIT} runs both
 * on PostgreSQL.
 */
@SpringBootTest
@ActiveProfiles("test")
class RefundConcurrencyTest {

    private static final int CONCURRENT_REQUESTS = 6;
    // How long a worker waits for the start signal: one that never gets it ends instead of waiting forever.
    private static final int FIRE_TIMEOUT_SECONDS = 10;
    private static final int RECEIPT_ID = 7;
    private static final int BUYER_ID = 42;
    private static final int PAYMENT_TX = 5000;
    private static final double TOTAL = 100.0;
    private static final LocalDateTime WHEN = LocalDateTime.of(2026, 6, 1, 12, 0);
    // What RefundService tells a request that finds the receipt already refunded.
    private static final String ALREADY_REFUNDED = "Order " + RECEIPT_ID + " has already been refunded";
    // Bounds every wait of the held-refund test, so a broken lock fails it instead of hanging it.
    private static final long AWAIT_SECONDS = 20;
    // Polled this often for the waiting session: H2 ends a lock wait after its lock timeout, so the held
    // refund is released as soon as the wait shows.
    private static final Duration LOCK_WAIT_POLL = Duration.ofMillis(10);

    @Autowired
    private RefundService refundService;
    @Autowired
    private OrderReceiptRepository orderReceiptRepository;
    @Autowired
    private JwtSessionManager jwtSessionManager;
    @Autowired
    private SpringDataOrderReceiptRepository receiptData;
    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private PaymentGateway paymentGateway;

    private String buyerToken;

    @BeforeEach
    void setUp() {
        receiptData.deleteAll();

        // Mocked gateway: refund returns a valid result (totalRefunded == requested amount) so
        // validateRefundResult passes; getId is non-null for the refund TransactionRecord.
        when(paymentGateway.getId()).thenReturn("test-gateway");
        when(paymentGateway.refund(anyInt(), anyDouble()))
                .thenAnswer(invocation -> validRefundOf(invocation.getArgument(0), invocation.getArgument(1)));

        // A refundable member receipt: holder == BUYER_ID, with an original charge so
        // getPaymentTransactionId() is present and getTotalAmount() == TOTAL.
        ReceiptLine line = new ReceiptLine(101, TOTAL, 2, 1, "15", WHEN);
        TransactionRecord charge = TransactionRecord.paymentCharge(PAYMENT_TX, "stub", TOTAL, "ILS", WHEN);
        orderReceiptRepository.save(OrderReceipt.forMember(RECEIPT_ID, BUYER_ID, TOTAL, List.of(line), WHEN, List.of(charge)));

        buyerToken = jwtSessionManager.generateToken(BUYER_ID, "buyer");
    }

    @Test
    void GivenManyConcurrentRefundsForSameReceipt_WhenRequestRefund_ThenExactlyOneWinsAndGatewayIsCalledExactlyOnce() throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_REQUESTS);
        CountDownLatch armed = new CountDownLatch(CONCURRENT_REQUESTS);
        CountDownLatch fire = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(CONCURRENT_REQUESTS);
        AtomicInteger refunded = new AtomicInteger();
        AtomicInteger alreadyRefunded = new AtomicInteger();
        ConcurrentLinkedQueue<Throwable> unexpected = new ConcurrentLinkedQueue<>();

        try {
            for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
                pool.submit(() -> {
                    try {
                        armed.countDown();
                        // release all requests at once to maximise the race
                        if (!fire.await(FIRE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("the race was never started");
                        }
                        refundService.requestRefund(buyerToken, RECEIPT_ID, "double-click");
                        refunded.incrementAndGet();
                    } catch (BusinessRuleViolationException refused) {
                        // A loser finds the receipt refunded, after waiting on the row lock if it overlapped
                        // the winner, and bails. That is the only BusinessRuleViolationException this path
                        // throws for a valid owner's receipt; any other is not the lost race.
                        if (ALREADY_REFUNDED.equals(refused.getMessage())) {
                            alreadyRefunded.incrementAndGet();
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
            assertTrue(finished.await(20, TimeUnit.SECONDS), "refunds did not finish in time");
        } finally {
            // Interrupts the workers even when an assertion above failed. One blocked where an interrupt does not
            // reach, such as a database call, can still outlast the test.
            pool.shutdownNow();
        }

        assertTrue(unexpected.isEmpty(), "only clean already-refunded failures expected, got: " + unexpected);
        assertEquals(1, refunded.get(), "exactly one refund should succeed");
        assertEquals(CONCURRENT_REQUESTS - 1, alreadyRefunded.get(),
                "every other request must bail as already-refunded");
        // The crux of #410: the gateway is hit exactly once despite the concurrent double-clicks.
        verify(paymentGateway, times(1)).refund(anyInt(), anyDouble());
        assertTrue(orderReceiptRepository.findByOrderReceiptId(RECEIPT_ID).orElseThrow().isFullyRefunded(),
                "the receipt is recorded as refunded");
    }

    // The slow WSEP refund runs while the first request holds the receipt's row lock, so a double-click's
    // second request must wait there, not read the still-unrefunded receipt and refund it again (#410).
    @Test
    void GivenAFirstRefundHeldInTheGateway_WhenASecondRefundOfTheSameReceiptArrives_ThenItWaitsOnTheRowLockAndNeverReachesTheGateway()
            throws Exception {
        CountDownLatch firstInGateway = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger gatewayRefunds = holdTheFirstGatewayRefund(firstInGateway, releaseFirst);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<RefundResultDTO> first = threads.submit(
                    () -> refundService.requestRefund(buyerToken, RECEIPT_ID, "first click"));
            awaitOrFail(firstInGateway, Duration.ofSeconds(AWAIT_SECONDS),
                    "arrangement: the first refund reaches the gateway");
            Future<RefundResultDTO> second = threads.submit(
                    () -> refundService.requestRefund(buyerToken, RECEIPT_ID, "second click"));
            await().atMost(Duration.ofSeconds(AWAIT_SECONDS)).pollDelay(Duration.ZERO).pollInterval(LOCK_WAIT_POLL)
                    .until(() -> second.isDone() || aSessionWaitsOnALock());
            boolean secondDoneWhileFirstHeld = second.isDone();
            int gatewayRefundsWhileFirstHeld = gatewayRefunds.get();
            releaseFirst.countDown();

            Throwable firstFailure = failureOf(() -> first.get(AWAIT_SECONDS, TimeUnit.SECONDS));
            Throwable secondFailure = failureOf(() -> second.get(AWAIT_SECONDS, TimeUnit.SECONDS));

            assertFalse(secondDoneWhileFirstHeld, "while the first refund is in the gateway, the second must wait on "
                    + "the receipt's row lock, but it finished with: " + secondFailure);
            assertEquals(1, gatewayRefundsWhileFirstHeld,
                    "while the first refund is in the gateway, the second must not reach it");
            assertNull(firstFailure, "the first refund succeeds");
            BusinessRuleViolationException refused = assertInstanceOf(BusinessRuleViolationException.class,
                    secondFailure, "once the first has committed, the second finds the receipt refunded");
            assertEquals(ALREADY_REFUNDED, refused.getMessage());
            verify(paymentGateway, times(1)).refund(anyInt(), anyDouble());
            assertTrue(orderReceiptRepository.findByOrderReceiptId(RECEIPT_ID).orElseThrow().isFullyRefunded(),
                    "the receipt is recorded as refunded");
        } finally {
            releaseFirst.countDown();
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

    /**
     * Makes the gateway hold its first refund until {@code release} opens, signalling {@code inGateway} once
     * it is held; every refund answers validly.
     *
     * @return the number of refunds the gateway has been asked for, counted as each call arrives
     */
    private AtomicInteger holdTheFirstGatewayRefund(CountDownLatch inGateway, CountDownLatch release) {
        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) {
                inGateway.countDown();
                awaitOrFail(release, Duration.ofSeconds(AWAIT_SECONDS), "the test releases the held refund");
            }
            return validRefundOf(invocation.getArgument(0), invocation.getArgument(1));
        }).when(paymentGateway).refund(anyInt(), anyDouble());
        return calls;
    }

    /** A refund answer that passes validation: the whole requested amount, with an id and a time. */
    private static RefundResultDTO validRefundOf(int paymentTransactionId, double amount) {
        return new RefundResultDTO("refund-" + paymentTransactionId, String.valueOf(paymentTransactionId), amount,
                WHEN, List.of(), List.of());
    }
}
