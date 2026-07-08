package com.ticketing.system.shared.persistence;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Per-key lock map used by the in-memory repository implementations to
 * provide the pessimistic-lock semantics promised by
 * {@link com.ticketing.system.shared.IRepository#findByIdForUpdate}.
 *
 * <p>Each {@code MemoryXxxRepository} owns one instance and routes its
 * {@code findByIdForUpdate} / {@code save} / {@code delete} / {@code unlock}
 * calls through it. Locks are {@link ReentrantLock}, so a single thread can
 * re-enter {@code findByIdForUpdate} for the same id without deadlocking
 * itself (useful when a service method calls another repo method that also
 * locks).
 *
 * <p>Locks are kept in a {@link ConcurrentHashMap} keyed by id; they're never
 * evicted, so the long-lived per-id lock identity is stable across calls.
 * Memory cost is one {@code ReentrantLock} per distinct id ever locked.
 */
public final class RepositoryLocks<ID> {

    private final Map<ID, ReentrantLock> locks = new ConcurrentHashMap<>();

    /** Acquire the exclusive lock for {@code id}. Blocks until available. */
    public void lock(ID id) {
        locks.computeIfAbsent(id, k -> new ReentrantLock()).lock();
    }

    /**
     * Release the lock for {@code id} if held by the current thread.
     *
     * @return true if a lock was released, false if no lock was held
     */
    public boolean unlock(ID id) {
        ReentrantLock lock = locks.get(id);
        if (lock != null && lock.isHeldByCurrentThread()) {
            lock.unlock();
            return true;
        }
        return false;
    }

    /** True iff the current thread holds the lock for {@code id}. */
    public boolean isHeldByCurrentThread(ID id) {
        ReentrantLock lock = locks.get(id);
        return lock != null && lock.isHeldByCurrentThread();
    }
}
