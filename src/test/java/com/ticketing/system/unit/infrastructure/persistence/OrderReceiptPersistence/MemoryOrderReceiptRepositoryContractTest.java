package com.ticketing.system.unit.infrastructure.persistence.OrderReceiptPersistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.ticketing.system.sales.application.port.out.OrderReceiptRepository;
import com.ticketing.system.sales.adapter.out.persistence.MemoryOrderReceiptRepository;
import com.ticketing.system.testutil.RepositoryLockProbe;

class MemoryOrderReceiptRepositoryContractTest extends IOrderReceiptRepositoryContractTest {

    @Override
    protected OrderReceiptRepository newRepository() {
        return new MemoryOrderReceiptRepository();
    }

    // IRepository's lock contract: reentrant, and free for another thread after as many unlocks as locks.
    // The second lockForUpdate would hang a non-reentrant lock, hence the timeout on a separate thread.
    // Not in the contract: the Jpa adapter's lock is a row lock and refuses to run outside a transaction
    // (JpaOrderReceiptRepositoryContractTest).
    @Test
    @Timeout(value = RepositoryLockProbe.TEST_TIMEOUT_SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void GivenALockTakenTwiceAndReleasedTwice_WhenAnotherThreadLocksTheSameId_ThenItGetsTheLock() throws Exception {
        OrderReceiptRepository repo = newRepository();
        repo.lockForUpdate(1);
        repo.lockForUpdate(1);
        repo.unlock(1);
        repo.unlock(1);

        RepositoryLockProbe.assertAnotherThreadCanLock(repo, 1);
    }
}
