package com.ticketing.system.sales.application.service;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.ticketing.system.shared.dto.CheckoutResultDTO;
import com.ticketing.system.shared.exception.IdempotencyConflictException;

/**
 * The checkout idempotency cache (#185): a replayed checkout returns the first result, a key reused by
 * another buyer is a conflict, and the cache is bounded, so a long-running instance cannot grow it
 * without limit. Past its capacity the oldest entry goes first.
 *
 * <p>Tests the package-private cache {@code CheckoutService} keeps its completed checkouts in, so the
 * bound can be shown with a small capacity instead of hundreds of real checkouts. Which entry a read
 * refreshes, if any, is not specified here: eviction is pinned only for entries never read after they
 * were cached.
 */
class CheckoutIdempotencyCacheTest {

    private static final String BUYER = "member:1";
    private static final String OTHER_BUYER = "member:2";
    private static final int SMALL_CAPACITY = 3;
    private static final int THREADS = 8;
    private static final int PUTS_PER_THREAD = 200;
    private static final long CONCURRENT_RUN_TIMEOUT_SECONDS = 30;

    @Test
    void GivenCachedCheckout_WhenTheSameBuyerReplaysTheKey_ThenTheFirstResultIsReturned() {
        CheckoutIdempotencyCache cache = new CheckoutIdempotencyCache(SMALL_CAPACITY);
        CheckoutResultDTO first = result(1);
        cache.putIfAbsent("idem-1", BUYER, first);

        assertEquals(Optional.of(first), cache.find("idem-1", BUYER));
    }

    @Test
    void GivenNothingCached_WhenLookedUp_ThenEmpty() {
        CheckoutIdempotencyCache cache = new CheckoutIdempotencyCache(SMALL_CAPACITY);

        assertEquals(Optional.empty(), cache.find("idem-1", BUYER));
    }

    @Test
    void GivenKeyCachedForAnotherBuyer_WhenLookedUp_ThenIdempotencyConflict() {
        CheckoutIdempotencyCache cache = new CheckoutIdempotencyCache(SMALL_CAPACITY);
        cache.putIfAbsent("idem-1", BUYER, result(1));

        assertThrows(IdempotencyConflictException.class, () -> cache.find("idem-1", OTHER_BUYER),
                "a key one buyer used must never return that buyer's purchase to another");
    }

    @Test
    void GivenKeyAlreadyCached_WhenCachedAgain_ThenTheFirstResultIsKept() {
        CheckoutIdempotencyCache cache = new CheckoutIdempotencyCache(SMALL_CAPACITY);
        CheckoutResultDTO first = result(1);
        cache.putIfAbsent("idem-1", BUYER, first);

        cache.putIfAbsent("idem-1", BUYER, result(2));

        assertEquals(Optional.of(first), cache.find("idem-1", BUYER));
    }

    @Test
    void GivenCacheFilledExactlyToCapacity_WhenLookedUp_ThenNothingWasEvicted() {
        CheckoutIdempotencyCache cache = new CheckoutIdempotencyCache(SMALL_CAPACITY);
        IntStream.range(0, SMALL_CAPACITY).forEach(i -> cache.putIfAbsent(key(i), BUYER, result(i)));

        assertAll(IntStream.range(0, SMALL_CAPACITY).mapToObj(i -> (Executable) () ->
                assertEquals(Optional.of(result(i)), cache.find(key(i), BUYER),
                        key(i) + " must still be cached while the cache is not over capacity")));
    }

    @Test
    void GivenCacheAtCapacity_WhenOneMoreCheckoutIsCached_ThenOnlyTheOldestIsEvicted() {
        CheckoutIdempotencyCache cache = new CheckoutIdempotencyCache(SMALL_CAPACITY);
        IntStream.rangeClosed(0, SMALL_CAPACITY).forEach(i -> cache.putIfAbsent(key(i), BUYER, result(i)));

        assertAll(
                () -> assertEquals(Optional.empty(), cache.find(key(0), BUYER), "the oldest entry must go first"),
                () -> assertEquals(Optional.of(result(1)), cache.find(key(1), BUYER)),
                () -> assertEquals(Optional.of(result(2)), cache.find(key(2), BUYER)),
                () -> assertEquals(Optional.of(result(SMALL_CAPACITY)), cache.find(key(SMALL_CAPACITY), BUYER)));
    }

    @Test
    void GivenManyMoreCheckoutsThanCapacity_WhenCached_ThenOnlyTheNewestCapacityRemain() {
        CheckoutIdempotencyCache cache = new CheckoutIdempotencyCache(SMALL_CAPACITY);
        int total = SMALL_CAPACITY * 10;
        IntStream.range(0, total).forEach(i -> cache.putIfAbsent(key(i), BUYER, result(i)));

        List<Integer> remaining = IntStream.range(0, total)
                .filter(i -> cache.find(key(i), BUYER).isPresent()).boxed().toList();

        assertEquals(IntStream.range(total - SMALL_CAPACITY, total).boxed().toList(), remaining);
    }

    @Test
    void GivenTheDefaultCapacity_WhenOneCheckoutPastItIsCached_ThenTheOldestIsEvicted() {
        CheckoutIdempotencyCache cache = new CheckoutIdempotencyCache(CheckoutIdempotencyCache.DEFAULT_CAPACITY);
        IntStream.range(0, CheckoutIdempotencyCache.DEFAULT_CAPACITY)
                .forEach(i -> cache.putIfAbsent(key(i), BUYER, result(i)));
        assertTrue(cache.find(key(0), BUYER).isPresent(), "nothing may be evicted at the default capacity");

        cache.putIfAbsent(key(CheckoutIdempotencyCache.DEFAULT_CAPACITY), BUYER, result(0));

        assertEquals(Optional.empty(), cache.find(key(0), BUYER),
                "one checkout past DEFAULT_CAPACITY must evict the oldest");
    }

    @ParameterizedTest(name = "capacity {0}")
    @ValueSource(ints = {0, -1})
    void GivenNonPositiveCapacity_WhenConstructed_ThenRejected(int capacity) {
        assertThrows(IllegalArgumentException.class, () -> new CheckoutIdempotencyCache(capacity));
    }

    // Checkouts complete on many request threads at once; the bound must hold under that too.
    @Test
    void GivenConcurrentCheckoutsOverflowingTheCapacity_WhenCached_ThenExactlyCapacityRemain() throws Exception {
        CheckoutIdempotencyCache cache = new CheckoutIdempotencyCache(SMALL_CAPACITY);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            List<Future<?>> writers = new ArrayList<>();
            for (int t = 0; t < THREADS; t++) {
                int thread = t;
                writers.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < PUTS_PER_THREAD; i++) {
                        int n = thread * PUTS_PER_THREAD + i;
                        cache.putIfAbsent(key(n), BUYER, result(n));
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> writer : writers) {
                writer.get(CONCURRENT_RUN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        long remaining = IntStream.range(0, THREADS * PUTS_PER_THREAD)
                .filter(n -> cache.find(key(n), BUYER).isPresent()).count();
        assertEquals(SMALL_CAPACITY, remaining,
                "expected exactly " + SMALL_CAPACITY + " cached checkouts, found " + remaining);
    }

    private static String key(int n) {
        return "idem-" + n;
    }

    private static CheckoutResultDTO result(int receiptId) {
        return new CheckoutResultDTO(100.0, receiptId, receiptId, List.of());
    }
}
