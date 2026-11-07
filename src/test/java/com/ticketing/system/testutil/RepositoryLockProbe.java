package com.ticketing.system.testutil;

import static org.junit.jupiter.api.Assertions.fail;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
        ExecutorService other = Executors.newSingleThreadExecutor();
        try {
            Future<?> lockedElsewhere = other.submit(() -> {
                repository.lockForUpdate(id);
                repository.unlock(id);
            });
            lockedElsewhere.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            fail("another thread could not take the lock on " + id + " within " + TIMEOUT_SECONDS
                    + " s, so the calling thread still holds it");
        } finally {
            other.shutdownNow();
        }
    }
}
