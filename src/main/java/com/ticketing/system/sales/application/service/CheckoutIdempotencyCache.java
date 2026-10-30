package com.ticketing.system.sales.application.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import com.ticketing.system.shared.dto.CheckoutResultDTO;
import com.ticketing.system.shared.exception.IdempotencyConflictException;

/**
 * The completed checkouts {@link CheckoutService} replays by idempotency key, so a double-clicked
 * "Buy" or a client retry returns the first purchase instead of charging again. Bounded: past its
 * capacity the oldest entry is evicted, so a long-running instance cannot grow it without limit.
 *
 * <p>Held in memory, so it is empty after a restart (SLR.7). Thread-safe: checkouts complete on
 * many request threads at once.
 */
final class CheckoutIdempotencyCache {

    /**
     * The capacity {@link CheckoutService} uses. A replay is a client retry that follows its original
     * within seconds or minutes, so an entry only has to outlive the checkouts completed in that window.
     * Ten thousand covers it at rates far above anything this single-instance platform sees, while the
     * entries (a receipt id, a total and the issued tickets of one purchase) stay a few megabytes at most.
     */
    static final int DEFAULT_CAPACITY = 10_000;

    private final Map<String, Entry> entries;

    /**
     * Creates an empty cache.
     *
     * @param capacity the most checkouts kept; the oldest is evicted once one more is cached
     * @throws IllegalArgumentException if {@code capacity} is less than 1
     */
    CheckoutIdempotencyCache(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be at least 1, was " + capacity);
        }
        // Insertion order, not access order: a replay must not keep an entry alive past newer checkouts.
        this.entries = new LinkedHashMap<>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
                return size() > capacity;
            }
        };
    }

    /**
     * Looks up the checkout completed under {@code idempotencyKey}.
     *
     * @param idempotencyKey the key the client sent with the checkout
     * @param buyerKey       the buyer replaying it, as {@link CheckoutService} forms it
     * @return the first result cached under the key, or empty if none is cached
     * @throws IdempotencyConflictException if the key is cached for a different buyer, so one buyer's
     *                                      purchase is never returned to another
     */
    synchronized Optional<CheckoutResultDTO> find(String idempotencyKey, String buyerKey) {
        Entry existing = entries.get(idempotencyKey);
        if (existing == null) {
            return Optional.empty();
        }
        if (!existing.buyerKey().equals(buyerKey)) {
            throw new IdempotencyConflictException(idempotencyKey);
        }
        return Optional.of(existing.result());
    }

    /**
     * Caches a completed checkout unless its key is already cached; the first result wins. Evicts the
     * oldest entry once the cache is over capacity.
     *
     * @param idempotencyKey the key the client sent with the checkout
     * @param buyerKey       the buyer who completed it
     * @param result         what the checkout returned
     */
    synchronized void putIfAbsent(String idempotencyKey, String buyerKey, CheckoutResultDTO result) {
        entries.putIfAbsent(idempotencyKey, new Entry(buyerKey, result));
    }

    private record Entry(String buyerKey, CheckoutResultDTO result) {
    }
}
