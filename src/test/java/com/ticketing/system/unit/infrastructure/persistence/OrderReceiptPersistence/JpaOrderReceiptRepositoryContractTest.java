package com.ticketing.system.unit.infrastructure.persistence.OrderReceiptPersistence;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.ticketing.system.sales.application.port.out.OrderReceiptRepository;
import com.ticketing.system.sales.adapter.out.persistence.JpaOrderReceiptRepository;
import com.ticketing.system.sales.adapter.out.persistence.SpringDataOrderReceiptRepository;
import com.ticketing.system.sales.domain.OrderReceipt;
import com.ticketing.system.sales.domain.ReceiptLine;
import com.ticketing.system.sales.domain.TransactionRecord;

/**
 * Runs the {@link IOrderReceiptRepositoryContractTest} suite against the JPA adapter on an embedded
 * H2 schema. {@code @Import} adds {@link JpaOrderReceiptRepository};
 * {@code @DataJpaTest} provides H2 + real {@code receipts}, {@code receipt_lines} and
 * {@code receipt_transactions} tables. Each test starts from an empty schema ({@link #cleanTable()})
 * so the suite is order-independent. CI never touches a real database.
 */
@DataJpaTest
@Import(JpaOrderReceiptRepository.class)
class JpaOrderReceiptRepositoryContractTest extends IOrderReceiptRepositoryContractTest {

    private static final LocalDateTime AT = LocalDateTime.of(2027, 7, 15, 17, 0);
    /** How long the second transaction may wait for the row lock before its statement is cancelled. */
    private static final int LOCKED_WRITE_TIMEOUT_SECONDS = 1;
    /** An upper bound on that transaction's whole attempt; never reached when the lock holds. */
    private static final long LOCKED_WRITE_AWAIT_SECONDS = 30;

    @Autowired
    private JpaOrderReceiptRepository repository;

    @Autowired
    private SpringDataOrderReceiptRepository data;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void cleanTable() {
        data.deleteAll();
    }

    @Override
    protected OrderReceiptRepository newRepository() {
        return repository;
    }

    // The lock is a row lock that lasts until the caller's transaction ends, so it means nothing without
    // one, and the adapter refuses it there (RefundService takes it inside its transaction). The Memory
    // adapter's lock needs none (MemoryOrderReceiptRepositoryContractTest).
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void GivenNoTransaction_WhenLockForUpdate_ThenItIsRefused() {
        repository.save(receipt(1));

        assertThrows(InvalidDataAccessApiUsageException.class, () -> repository.lockForUpdate(1));
    }

    // Unlike every other Jpa adapter's, this lockForUpdate takes a real row lock (SELECT ... FOR UPDATE,
    // upstream#410) that lasts until the caller's transaction ends, so two refunds of one receipt cannot both reach
    // the gateway. Meanwhile another transaction's write of the row waits, and gives up with a
    // QueryTimeoutException: on H2 at its own lock timeout (error 50200, 2 s by default), on PostgreSQL,
    // which sets no lock timeout, when the 1 s statement timeout cancels it (SQL state 57014). Outside
    // the test transaction, so the receipt is committed and the second connection sees it.
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void GivenAReceiptLockedInOneTransaction_WhenAnotherTransactionWritesItMeanwhile_ThenTheWriteWaitsAndTimesOut() {
        repository.save(receipt(1));

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            repository.lockForUpdate(1);

            CompletableFuture<Integer> otherWrite = CompletableFuture.supplyAsync(() -> {
                JdbcTemplate otherConnection = new JdbcTemplate(dataSource);
                otherConnection.setQueryTimeout(LOCKED_WRITE_TIMEOUT_SECONDS);
                return otherConnection.update("update receipts set total_price = 60 where receipt_id = 1");
            });

            ExecutionException failed = assertThrows(ExecutionException.class,
                    () -> otherWrite.get(LOCKED_WRITE_AWAIT_SECONDS, TimeUnit.SECONDS),
                    "the other transaction wrote the receipt while it was locked");
            assertInstanceOf(QueryTimeoutException.class, failed.getCause(), "a lock wait that timed out");
        });

        assertEquals(50.0, jdbc.queryForObject("select total_price from receipts where receipt_id = 1", Double.class));
    }

    // A write waits for a shared lock too (PostgreSQL's FOR SHARE), so the case above cannot tell the
    // exclusive lock that keeps a second refund from the gateway from a shared one: two refunds that each
    // took a shared lock would both go on. Only a second locker shows it. On H2 a shared lock is a FOR
    // UPDATE as well (see ADR-015), so only PostgreSQL's run of this case catches PESSIMISTIC_READ.
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void GivenAReceiptLockedInOneTransaction_WhenAnotherTransactionLocksItMeanwhile_ThenItWaitsAndTimesOut() {
        repository.save(receipt(1));
        TransactionTemplate otherTransaction = new TransactionTemplate(transactionManager);
        otherTransaction.setTimeout(LOCKED_WRITE_TIMEOUT_SECONDS);
        ExecutorService otherThread = Executors.newSingleThreadExecutor();
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                repository.lockForUpdate(1);

                CompletableFuture<Void> otherLock = CompletableFuture.runAsync(
                        () -> otherTransaction.executeWithoutResult(other -> repository.lockForUpdate(1)),
                        otherThread);

                ExecutionException failed = assertThrows(ExecutionException.class,
                        () -> otherLock.get(LOCKED_WRITE_AWAIT_SECONDS, TimeUnit.SECONDS),
                        "a second transaction took the receipt's lock while the first held it");
                assertLockWaitTimedOut(failed.getCause());
            });
        } finally {
            otherThread.shutdownNow();
        }
    }

    // What the adapter's lock throws once it gives up waiting; any other failure, a missing table or a
    // lost connection, is not a lock wait. Hibernate translates by database: H2's lock timeout (error
    // 50200) becomes a PessimisticLockingFailureException, and PostgreSQL's statement cancelled by the
    // transaction's 1 s timeout (SQL state 57014) a QueryTimeoutException.
    private static void assertLockWaitTimedOut(Throwable failure) {
        assertTrue(failure instanceof PessimisticLockingFailureException || failure instanceof QueryTimeoutException,
                "expected a lock wait that timed out, got " + failure);
    }

    // A new adapter instance models a restarted process (#19, as JpaTicketRepositoryContractTest does for
    // tickets): the id sequence must resume above the rows a previous boot persisted, not start at 1.
    @Test
    void GivenReceiptsPersistedByAPreviousBoot_WhenAFreshAdapterMintsAnId_ThenItIsAboveTheExistingMax() {
        data.saveAllAndFlush(List.of(receipt(1), receipt(2), receipt(3)));

        int id = new JpaOrderReceiptRepository(data).nextId();

        assertTrue(id > 3, "receipt id after a restart: " + id);
    }

    private static OrderReceipt receipt(int id) {
        return OrderReceipt.forMember(id, 7, 50.0, List.of(new ReceiptLine(id * 10, 50.0, 5, 1, "A1", AT)), AT);
    }

    // Review finding 4 on #129: a row written before refund state moved onto the lines (#20) records a
    // refund only in the order-level flag. Since V6 (#175) its lines read NONE from their rows, the
    // shape the raw SQL below writes. Only a database that predates the line state can hold one, so
    // this case is JPA-only. It must fail closed: nothing on it may be refunded again. It runs outside
    // the test transaction so the raw SQL commits and the adapter's own read sees it.
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void GivenReceiptRefundedBeforeLineStateExisted_WhenLoaded_ThenItCountsAsFullyRefunded() {
        repository.save(OrderReceipt.forMember(1, 7, 100.0, List.of(
                new ReceiptLine(10, 50.0, 5, 1, "A1", AT),
                new ReceiptLine(11, 50.0, 6, 1, "A2", AT)), AT));
        jdbc.update("update receipts set is_refunded = true where receipt_id = 1");
        jdbc.update("update receipt_lines set refund_state = 'NONE' where receipt_id = 1");

        OrderReceipt legacy = repository.findByOrderReceiptId(1).orElseThrow();

        assertTrue(legacy.getReceiptLines().stream().allMatch(ReceiptLine::isRefunded));
        assertTrue(legacy.isFullyRefunded());
        assertEquals(0.0, legacy.netAmount(), "it earns nothing in the revenue figures");
        assertDoesNotThrow(legacy::checkInvariants, "the invariant accepts a pre-#129 row");
        assertTrue(legacy.hasRefundedOrPendingLines(), "a whole-order member refund must be refused");
        assertTrue(legacy.claimRefundForEvent(5).isEmpty(), "an event cancellation must claim nothing");
    }

    // Review finding 1 on #132: while #129's code ran, refunding one event of an order also set the
    // order-level flag. Such a row must read as partly refunded, and its other event must still be
    // claimable when that event is cancelled.
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void GivenPartlyRefundedRowWithTheOrderFlagSet_WhenLoaded_ThenItIsPartlyRefundedAndItsOtherEventCanBeClaimed() {
        OrderReceipt partly = OrderReceipt.forMember(2, 7, 100.0, List.of(
                new ReceiptLine(20, 50.0, 5, 1, "B1", AT),
                new ReceiptLine(21, 50.0, 6, 1, "B2", AT)), AT);
        partly.claimRefundForEvent(5);
        partly.completeEventRefund(5, TransactionRecord.refund("refund-5", "WSEP", 50.0, "ILS", AT));
        repository.save(partly);
        jdbc.update("update receipts set is_refunded = true where receipt_id = 2");

        OrderReceipt loaded = repository.findByOrderReceiptId(2).orElseThrow();

        assertFalse(loaded.isFullyRefunded());
        assertEquals(50.0, loaded.netAmount());
        assertEquals(50.0, loaded.claimRefundForEvent(6).orElseThrow());
    }
}
