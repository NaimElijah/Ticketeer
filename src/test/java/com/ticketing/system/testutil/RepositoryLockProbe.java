package com.ticketing.system.testutil;

import static org.junit.jupiter.api.Assertions.fail;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.ticketing.system.shared.IRepository;

/**
 * Checks the release half of the {@link IRepository} lock contract from a second thread: once the test
 * thread has unlocked an id, another thread can take and release the lock on it. The Memory adapters'
 * locks block that thread while the id is still held; the Jpa adapters' no-op locks never do.
 */
public final class RepositoryLockProbe {

    /** How long the other thread may wait for the lock before the check fails; never reached when it passes. */
    public static final long TIMEOUT_SECONDS = 10;

    /**
     * A bound for a whole test that uses this probe, longer than {@link #TIMEOUT_SECONDS} so the probe's own
     * failure message is the one reported. A JUnit {@code @Timeout} on a separate thread uses it, because a
     * non-reentrant lock would hang the test thread itself.
     */
    public static final long TEST_TIMEOUT_SECONDS = 2 * TIMEOUT_SECONDS;

    private RepositoryLockProbe() {
    }

    /**
     * Fails unless another thread takes and releases the lock on {@code id} within {@link #TIMEOUT_SECONDS}.
     *
     * @param repository the adapter under test
     * @param id the id whose lock the calling thread has released
     * @param <ID> the repository's id type
     * @throws InterruptedException if the calling thread is interrupted while it waits
     * @throws ExecutionException if the other thread's lock or unlock throws
     */
    public static <ID> void assertAnotherThreadCanLock(IRepository<?, ID> repository, ID id)
            throws InterruptedException, ExecutionException {
        assertAnotherThreadCan("take the lock on " + id, () -> {
            repository.lockForUpdate(id);
            repository.unlock(id);
        });
    }

    /**
     * Fails unless another thread completes {@code lockAndUnlock} within {@link #TIMEOUT_SECONDS}.
     *
     * @param what the lock, for the failure message
     * @param lockAndUnlock takes and releases the lock
     * @throws InterruptedException if the calling thread is interrupted while it waits
     * @throws ExecutionException if {@code lockAndUnlock} throws
     */
    public static void assertAnotherThreadCan(String what, Runnable lockAndUnlock)
            throws InterruptedException, ExecutionException {
        ExecutorService other = Executors.newSingleThreadExecutor();
        try {
            other.submit(lockAndUnlock).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            fail("another thread could not " + what + " within " + TIMEOUT_SECONDS
                    + " s, so the calling thread still holds it");
        } finally {
            other.shutdownNow();
        }
    }
}
