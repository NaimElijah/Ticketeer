package com.ticketing.system.shared.persistence;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Per-key read/write locks for Event lifecycle synchronization.
 *
 * Read lock = buyer inventory operation.
 * Multiple buyers can reserve/release/confirm concurrently because StandingZone
 * and SeatedZone protect the concrete inventory.
 *
 * Write lock = structural or lifecycle event change.
 * Blocks all buyer operations.
 */
public final class RepositoryReadWriteLocks<ID> {

    private final Map<ID, ReentrantReadWriteLock> locks = new ConcurrentHashMap<>();

    public void lockRead(ID id) {
        locks.computeIfAbsent(id, k -> new ReentrantReadWriteLock(true))
                .readLock()
                .lock();
    }

    public void unlockRead(ID id) {
        ReentrantReadWriteLock lock = locks.get(id);
        if (lock != null && lock.getReadHoldCount() > 0) {
            lock.readLock().unlock();
        }
    }

    public void lockWrite(ID id) {
        locks.computeIfAbsent(id, k -> new ReentrantReadWriteLock(true))
                .writeLock()
                .lock();
    }

    public void unlockWrite(ID id) {
        ReentrantReadWriteLock lock = locks.get(id);
        if (lock != null && lock.isWriteLockedByCurrentThread()) {
            lock.writeLock().unlock();
        }
    }

    public boolean isReadHeldByCurrentThread(ID id) {
        ReentrantReadWriteLock lock = locks.get(id);
        return lock != null && lock.getReadHoldCount() > 0;
    }

    public boolean isWriteHeldByCurrentThread(ID id) {
        ReentrantReadWriteLock lock = locks.get(id);
        return lock != null && lock.isWriteLockedByCurrentThread();
    }
}
