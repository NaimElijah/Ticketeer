package com.ticketing.system.unit.infrastructure.persistence.AdminPersistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.identity.domain.Admin;
import com.ticketing.system.identity.application.port.out.AdminRepository;

/**
 * Behavioural contract every {@link AdminRepository} implementation must satisfy.
 * The Memory and JPA adapters each subclass this with their own {@code newRepository()}
 * factory, so the identical tests run against both — the V3 "validate the swap behind
 * the unchanged port" safety net.
 */
abstract class IAdminRepositoryContractTest {

    protected abstract AdminRepository newRepository();

    private AdminRepository repo;

    @BeforeEach
    void setUp() {
        repo = newRepository();
    }

    @Test
    void save_thenFindById_returnsTheSavedAdmin() {
        repo.save(new Admin(1, "admin", "HASH", true));

        Admin found = repo.findById(1);
        assertEquals("admin", found.getUsername());
        assertTrue(found.isDefault());
    }

    @Test
    void save_thenFindByUsername_returnsTheSavedAdmin() {
        repo.save(new Admin(7, "root", "HASH", false));

        Admin found = repo.findByUsername("root");
        assertEquals(7, found.getId());
    }

    @Test
    void findById_returnsNullWhenMissing() {
        assertNull(repo.findById(9999));
    }

    @Test
    void findByUsername_returnsNullWhenMissing() {
        assertNull(repo.findByUsername("ghost"));
    }

    @Test
    void findByUsername_nullArgument_returnsNull() {
        assertNull(repo.findByUsername(null));
    }

    @Test
    void existsAny_falseWhenEmpty_trueAfterSave() {
        assertFalse(repo.existsAny());
        repo.save(new Admin(1, "admin", "HASH", true));
        assertTrue(repo.existsAny());
    }

    @Test
    void findAll_returnsEverySavedAdmin() {
        assertTrue(repo.findAll().isEmpty());
        repo.save(new Admin(1, "admin", "HASH", true));
        repo.save(new Admin(2, "second", "HASH2", false));
        assertEquals(2, repo.findAll().size());
    }

    @Test
    void lockForUpdateThenUnlock_isCallableWithoutThrowing() {
        // Admin is never contended (created once at synchronized platform init); the lock
        // primitives must simply be callable. The JPA adapter makes them no-ops (optimistic
        // @Version handles any future contention).
        repo.lockForUpdate(1);
        repo.unlock(1);
    }
}
