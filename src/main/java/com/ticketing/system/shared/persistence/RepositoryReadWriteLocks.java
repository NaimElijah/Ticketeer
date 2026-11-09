package com.ticketing.system.shared.persistence;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Per-key read/write locks for Event lifecycle synchronization, used by {@code MemoryEventRepository}.
 *
 * <p>Read lock = buyer inventory operation.
 * Multiple buyers can reserve/release/confirm concurrently because StandingZone
 * and SeatedZone protect the concrete inventory.
 *
 * <p>Write lock = structural or lifecycle event change.
 * Blocks all buyer operations.
 *
 * <p>Locks are created on first use and never evicted.
 */
public final class RepositoryReadWriteLocks<ID> {

    private final Map<ID, ReentrantReadWriteLock> locks = new ConcurrentHashMap<>();

    /**
     * Acquires the shared read lock for {@code id}, waiting while another thread holds the write lock.
     * Many threads can hold it at once; the lock is fair.
     *
     * @param id the event id
     */
    public void lockRead(ID id) {
        locks.computeIfAbsent(id, k -> new ReentrantReadWriteLock(true))
                .readLock()
                .lock();
    }

    /**
     * Releases one read hold of the current thread on {@code id}; does nothing when the thread holds none.
     *
     * @param id the event id
     */
    public void unlockRead(ID id) {
        ReentrantReadWriteLock lock = locks.get(id);
        if (lock != null && lock.getReadHoldCount() > 0) {
            lock.readLock().unlock();
        }
    }

    /**
     * Acquires the exclusive write lock for {@code id}, waiting for every other holder. Reentrant for the
     * same thread. A thread that holds the read lock for {@code id} must not ask for the write lock: it
     * would wait for itself.
     *
     * @param id the event id
     */
    public void lockWrite(ID id) {
        locks.computeIfAbsent(id, k -> new ReentrantReadWriteLock(true))
                .writeLock()
                .lock();
    }

    /**
     * Releases the write lock for {@code id}; does nothing when the current thread does not hold it.
     *
     * @param id the event id
     */
    public void unlockWrite(ID id) {
        ReentrantReadWriteLock lock = locks.get(id);
        if (lock != null && lock.isWriteLockedByCurrentThread()) {
            lock.writeLock().unlock();
        }
    }

    /**
     * Tells whether the current thread holds the read lock for {@code id}.
     *
     * @param id the event id
     */
    public boolean isReadHeldByCurrentThread(ID id) {
        ReentrantReadWriteLock lock = locks.get(id);
        return lock != null && lock.getReadHoldCount() > 0;
    }

    /**
     * Tells whether the current thread holds the write lock for {@code id}.
     *
     * @param id the event id
     */
    public boolean isWriteHeldByCurrentThread(ID id) {
        ReentrantReadWriteLock lock = locks.get(id);
        return lock != null && lock.isWriteLockedByCurrentThread();
    }
}
