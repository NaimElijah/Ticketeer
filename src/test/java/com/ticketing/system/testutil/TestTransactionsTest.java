package com.ticketing.system.testutil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import com.ticketing.system.testutil.TestTransactions.RecordingTransactionManager;

/**
 * The recording manager tells a commit from a rollback, which a template's re-thrown exception alone
 * cannot (#189).
 */
class TestTransactionsTest {

    private final RecordingTransactionManager transactions = TestTransactions.recordingManager();
    private final TransactionTemplate template = new TransactionTemplate(transactions);

    @Test
    void GivenACallbackThatReturns_WhenRunInATemplate_ThenItCommitsOnce() {
        template.executeWithoutResult(status -> { });

        assertEquals(1, transactions.commits(), "commits");
        assertEquals(0, transactions.rollbacks(), "rollbacks");
    }

    @Test
    void GivenACallbackThatThrows_WhenRunInATemplate_ThenItRollsBackAndTheFailurePropagates() {
        IllegalStateException failure = new IllegalStateException("save failed");

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
            () -> template.executeWithoutResult(status -> {
                throw failure;
            }));

        assertSame(failure, thrown, "the template re-throws the callback's own failure");
        assertEquals(0, transactions.commits(), "commits");
        assertEquals(1, transactions.rollbacks(), "rollbacks");
    }

    @Test
    void GivenACallbackThatMarksItsTransactionRollbackOnly_WhenRunInATemplate_ThenItRollsBack() {
        template.executeWithoutResult(status -> status.setRollbackOnly());

        assertEquals(0, transactions.commits(), "a rollback-only transaction must not count as committed");
        assertEquals(1, transactions.rollbacks(), "rollbacks");
    }

    @Test
    void GivenACallbackThatSwallowsItsFailure_WhenRunInATemplate_ThenItCommits() {
        template.executeWithoutResult(status -> {
            try {
                throw new IllegalStateException("save failed");
            } catch (IllegalStateException swallowed) {
                // The callback hides its failure, so the template commits whatever it wrote.
            }
        });

        assertEquals(1, transactions.commits(), "a swallowed failure commits, which a rollback assertion must catch");
        assertEquals(0, transactions.rollbacks(), "rollbacks");
    }
}
