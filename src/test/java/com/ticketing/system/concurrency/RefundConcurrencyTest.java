package com.ticketing.system.concurrency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.ticketing.system.Core.Application.dto.RefundResultDTO;
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
 * <p>Under jpa the receipt's {@code lockForUpdate} used to be a no-op, so two concurrent refund
 * requests for the same receipt could both pass {@code wasRefunded()} and both call
 * {@code paymentGateway.refund()} — refunding the buyer twice ({@code @Version} only fails the second
 * SAVE, <em>after</em> the gateway was already hit). This test fires N refunds at the same receipt
 * simultaneously and asserts the gateway is called <strong>exactly once</strong>, proving the real
 * {@code SELECT … FOR UPDATE} row lock serialises the critical section so every loser sees
 * {@code is_refunded=true} and bails before any second gateway call.
 *
 * <p>Profiles {@code jpa,test}: JPA repositories + H2. The gateway is mocked so {@code refund()} calls
 * can be counted; the platform-init runner that would probe it is {@code @Profile("!test")}, so it
 * stays off and the unstubbed mock is harmless at startup.
 */
@SpringBootTest
@ActiveProfiles({"jpa", "test"})
class RefundConcurrencyTest {

    private static final int CONCURRENT_REQUESTS = 6;
    private static final int RECEIPT_ID = 7;
    private static final int BUYER_ID = 42;
    private static final int PAYMENT_TX = 5000;
    private static final double TOTAL = 100.0;
    private static final LocalDateTime WHEN = LocalDateTime.of(2026, 6, 1, 12, 0);

    @Autowired
    private RefundService refundService;
    @Autowired
    private OrderReceiptRepository orderReceiptRepository;
    @Autowired
    private JwtSessionManager jwtSessionManager;
    @Autowired
    private SpringDataOrderReceiptRepository receiptData;

    @MockitoBean
    private PaymentGateway paymentGateway;

    private String buyerToken;

    @BeforeEach
    void setUp() {
        receiptData.deleteAll();

        // Mocked gateway: refund returns a valid result (totalRefunded == requested amount) so
        // validateRefundResult passes; getId is non-null for the refund TransactionRecord.
        when(paymentGateway.getId()).thenReturn("test-gateway");
        when(paymentGateway.refund(anyInt(), anyDouble())).thenAnswer(invocation -> {
            int txId = invocation.getArgument(0);
            double amount = invocation.getArgument(1);
            return new RefundResultDTO("refund-" + txId, String.valueOf(txId), amount, LocalDateTime.now(),
                    List.of(), List.of());
        });

        // A refundable member receipt: holder == BUYER_ID, with an original charge so
        // getPaymentTransactionId() is present and getTotalAmount() == TOTAL.
        ReceiptLine line = new ReceiptLine(101, TOTAL, 2, 1, "15", WHEN);
        TransactionRecord charge = TransactionRecord.paymentCharge(PAYMENT_TX, "stub", TOTAL, "ILS", WHEN);
        orderReceiptRepository.save(OrderReceipt.forMember(RECEIPT_ID, BUYER_ID, TOTAL, List.of(line), List.of(charge)));

        buyerToken = jwtSessionManager.generateToken(BUYER_ID, "buyer");
    }

    @Test
    void givenManyConcurrentRefundsForSameReceipt_thenGatewayIsCalledExactlyOnce() throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_REQUESTS);
        CountDownLatch armed = new CountDownLatch(CONCURRENT_REQUESTS);
        CountDownLatch fire = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(CONCURRENT_REQUESTS);
        AtomicInteger refunded = new AtomicInteger();
        AtomicInteger alreadyRefunded = new AtomicInteger();
        ConcurrentLinkedQueue<Throwable> unexpected = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
            pool.submit(() -> {
                try {
                    armed.countDown();
                    fire.await(); // release all requests at once to maximise the race
                    refundService.requestRefund(buyerToken, RECEIPT_ID, "double-click");
                    refunded.incrementAndGet();
                } catch (BusinessRuleViolationException alreadyDone) {
                    alreadyRefunded.incrementAndGet(); // the loser saw is_refunded=true and bailed
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
        pool.shutdownNow();

        assertTrue(unexpected.isEmpty(), "only clean already-refunded failures expected, got: " + unexpected);
        assertEquals(1, refunded.get(), "exactly one refund should succeed");
        assertEquals(CONCURRENT_REQUESTS - 1, alreadyRefunded.get(),
                "every other request must bail as already-refunded");
        // The crux of #410: the gateway is hit exactly once despite the concurrent double-clicks.
        verify(paymentGateway, times(1)).refund(anyInt(), anyDouble());
        assertTrue(orderReceiptRepository.findByOrderReceiptId(RECEIPT_ID).orElseThrow().wasRefunded(),
                "the receipt is recorded as refunded");
    }
}
