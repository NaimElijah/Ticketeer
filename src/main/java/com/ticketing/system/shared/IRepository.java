package com.ticketing.system.shared;

/**
 * Generic synchronization contract every aggregate-root repository implements.
 *
 * <p>The course's "one {@code IXxxRepository} per aggregate root" rule still
 * holds — each domain repository extends this with its aggregate-specific
 * lookup, save, and query methods (those stay where they are, with whatever
 * signatures already exist). This base only adds the per-aggregate write-lock
 * primitives needed for safe concurrent updates.
 *
 * <h2>Why this exists</h2>
 *
 * <p>With many concurrent users hitting the same aggregate (the canonical
 * case: two buyers reserving the same seat at the same millisecond), naive
 * read-modify-write goes wrong even with thread-safe collections:
 * <pre>
 * Thread A: event = repo.findById(42);    // status=ON_SALE
 * Thread B: event = repo.findById(42);    // status=ON_SALE
 * Thread A: event.cancel(); repo.save(event);  // -&gt; CANCELLED
 * Thread B: event.sellOut(); repo.save(event); // overwrites A's change
 * </pre>
 *
 * <p>The {@link #lockForUpdate(Object)} → mutate → {@link #unlock(Object)}
 * pattern serializes writes to the same aggregate id:
 * <pre>
 * repo.lockForUpdate(eventId);
 * try {
 *     Event event = repo.findById(eventId);
 *     event.cancel();
 *     repo.save(event);
 * } finally {
 *     repo.unlock(eventId);
 * }
 * </pre>
 *
 * <h2>Implementation</h2>
 *
 * <ul>
 *   <li><b>In-memory implementations</b> (current) use a per-id
 *       {@link java.util.concurrent.locks.ReentrantLock} via
 *       {@code RepositoryLocks}. Reentrant: same thread re-locking is safe.</li>
 *   <li><b>JPA-backed implementations</b> (V3) make {@code lockForUpdate} and
 *       {@code unlock} <b>no-ops</b>. Concurrency is handled by {@code @Version}
 *       OPTIMISTIC locking on the aggregate — at the granularity of the real
 *       contention (e.g. per-{@code Seat} for Event, so independent reservations
 *       don't conflict) — and the service use-case retries on
 *       {@code OptimisticLockException} (a fresh transaction re-reads the current
 *       version). No pessimistic {@code SELECT ... FOR UPDATE} is used.</li>
 * </ul>
 *
 * <p><b>Always pair lockForUpdate with unlock in a try/finally.</b>
 * Forgetting leaks the lock and deadlocks subsequent writes to the same id.
 *
 * @param <T>  aggregate type (informational — base contract doesn't constrain methods on it)
 * @param <ID> identifier type
 */
public interface IRepository<T, ID> {

    /**
     * Acquire an exclusive write lock for {@code id}. Blocks until the lock
     * is available. Reentrant — same thread may acquire repeatedly without
     * deadlocking itself.
     */
    void lockForUpdate(ID id);

    /**
     * Release the write lock for {@code id} held by the current thread.
     * No-op if no lock is held. Must be called from a {@code finally} block.
     */
    void unlock(ID id);
}
