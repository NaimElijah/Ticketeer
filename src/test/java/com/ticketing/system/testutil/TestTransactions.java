package com.ticketing.system.testutil;

import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * Test helper: a {@link PlatformTransactionManager} with no datasource behind it, so a
 * {@code TransactionTemplate} runs its callback inline, that counts the transactions it commits and
 * the ones it rolls back.
 *
 * <p>The mock- and fake-based suites construct services such as {@code CheckoutService} by hand and
 * have no real datasource. An exception thrown inside a callback still propagates: the template rolls
 * back, then re-throws. That alone cannot tell a rollback from a callback that swallowed its failure
 * and committed, so a test that claims a rollback asserts {@link RecordingTransactionManager#rollbacks()}.
 * Nothing is flushed at commit, so commit-time behaviour ({@code @Version}, constraints) is left to the
 * JPA-profile tests.
 */
public final class TestTransactions {

    private TestTransactions() {
    }

    /**
     * A manager that changes no data and records each commit and rollback; create one per service
     * under test, so its counts are that service's.
     *
     * @return a fresh manager with both counts at zero
     */
    public static RecordingTransactionManager noOpManager() {
        return new RecordingTransactionManager();
    }

    /**
     * Counts the transactions committed and rolled back. A commit of a transaction marked rollback-only
     * counts as a rollback, as Spring's own managers roll it back. Safe to share between threads.
     */
    public static final class RecordingTransactionManager implements PlatformTransactionManager {

        private final AtomicInteger commits = new AtomicInteger();
        private final AtomicInteger rollbacks = new AtomicInteger();

        private RecordingTransactionManager() {
        }

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            if (status.isRollbackOnly()) {
                rollbacks.incrementAndGet();
            } else {
                commits.incrementAndGet();
            }
        }

        @Override
        public void rollback(TransactionStatus status) {
            rollbacks.incrementAndGet();
        }

        /** How many transactions committed. */
        public int commits() {
            return commits.get();
        }

        /** How many transactions rolled back, a commit of a rollback-only one included. */
        public int rollbacks() {
            return rollbacks.get();
        }
    }
}
