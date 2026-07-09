package com.ticketing.system.testutil;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * Test helper: a no-op {@link PlatformTransactionManager} that makes a {@code TransactionTemplate}
 * run its callback inline — begin / commit / rollback are no-ops.
 *
 * <p>The mock- and fake-based checkout suites construct {@code CheckoutService} by hand and have no
 * real datasource. This lets the real Phase-3 callback run as before, and an exception thrown inside
 * it still propagates (rollback is a no-op, then the template re-throws), so the existing
 * failure / refund / rollback assertions hold unchanged. The genuine commit-time transaction
 * behaviour is exercised by the JPA-profile tests instead.
 */
public final class TestTransactions {

    private TestTransactions() {
    }

    public static PlatformTransactionManager noOpManager() {
        return new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {
            }

            @Override
            public void rollback(TransactionStatus status) {
            }
        };
    }
}
