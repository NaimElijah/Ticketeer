package com.ticketing.system.unit.infrastructure.persistence.UserPersistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.ticketing.system.identity.application.port.out.UserRepository;
import com.ticketing.system.identity.adapter.out.persistence.MemoryUserRepository;
import com.ticketing.system.identity.domain.User;
import com.ticketing.system.shared.exception.DuplicateUsernameException;

class MemoryUserRepositoryContractTest extends IUserRepositoryContractTest {

    private static final int RACING_SAVES = 16;
    private static final long AWAIT_SECONDS = 10;

    @Override
    protected UserRepository newRepository() {
        return new MemoryUserRepository();
    }

    // The Memory adapter has no database constraint behind it, so its uniqueness check and its
    // insert must be one atomic step (#185). A correct adapter passes every run; a check-then-put
    // one fails whenever two saves interleave.
    @Test
    void GivenManySavesOfOneUsernameAtOnce_WhenSaved_ThenExactlyOneUserHoldsIt() throws InterruptedException {
        UserRepository repository = newRepository();
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger saved = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();
        ConcurrentLinkedQueue<Throwable> unexpected = new ConcurrentLinkedQueue<>();
        ExecutorService pool = Executors.newFixedThreadPool(RACING_SAVES);
        try {
            for (int i = 0; i < RACING_SAVES; i++) {
                User racer = new User(repository.nextId(), "alice", "alice" + i + "@example.com", "HASH", 20);
                pool.submit(() -> {
                    try {
                        start.await();
                        repository.save(racer);
                        saved.incrementAndGet();
                    } catch (DuplicateUsernameException e) {
                        refused.incrementAndGet();
                    } catch (Throwable t) {
                        unexpected.add(t);
                    }
                });
            }
            start.countDown();
        } finally {
            pool.shutdown();
        }
        assertTrue(pool.awaitTermination(AWAIT_SECONDS, TimeUnit.SECONDS), "the saves did not finish");

        assertEquals(List.of(), List.copyOf(unexpected), "a save failed with something other than a refusal");
        assertEquals(1, saved.get(), "exactly one of " + RACING_SAVES + " saves may take the username");
        assertEquals(RACING_SAVES - 1, refused.get(), "every other save must be refused");
        assertEquals(1, repository.findAll().stream().filter(u -> "alice".equals(u.getUsername())).count(),
                "exactly one stored user may hold the username");
    }
}
