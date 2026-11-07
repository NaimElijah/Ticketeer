package com.ticketing.system.unit.infrastructure.persistence.AdminPersistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.ticketing.system.identity.domain.Admin;
import com.ticketing.system.identity.application.port.out.AdminRepository;
import com.ticketing.system.shared.exception.DuplicateUsernameException;
import com.ticketing.system.testutil.RepositoryLockProbe;

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
    void GivenSavedAdmin_WhenFindById_ThenReturnsTheSavedAdmin() {
        repo.save(new Admin(1, "admin", "HASH", true));

        Admin found = repo.findById(1);
        assertEquals("admin", found.getUsername());
        assertTrue(found.isDefault());
    }

    @Test
    void GivenSavedAdmin_WhenFindByUsername_ThenReturnsTheSavedAdmin() {
        repo.save(new Admin(7, "root", "HASH", false));

        Admin found = repo.findByUsername("root");
        assertEquals(7, found.getId());
    }

    @Test
    void GivenNoSuchAdmin_WhenFindById_ThenReturnsNull() {
        assertNull(repo.findById(9999));
    }

    @Test
    void GivenNoSuchAdmin_WhenFindByUsername_ThenReturnsNull() {
        assertNull(repo.findByUsername("ghost"));
    }

    @Test
    void GivenNullUsername_WhenFindByUsername_ThenReturnsNull() {
        assertNull(repo.findByUsername(null));
    }

    @Test
    void GivenARepositoryBeforeAndAfterASave_WhenExistsAny_ThenFalseWhenEmptyTrueAfterSave() {
        assertFalse(repo.existsAny());
        repo.save(new Admin(1, "admin", "HASH", true));
        assertTrue(repo.existsAny());
    }

    @Test
    void GivenARepositoryBeforeAndAfterTwoSaves_WhenFindAll_ThenEmptyBeforeAndReturnsTwoAdminsAfter() {
        assertTrue(repo.findAll().isEmpty());
        repo.save(new Admin(1, "admin", "HASH", true));
        repo.save(new Admin(2, "second", "HASH2", false));
        assertEquals(Set.of(1, 2), repo.findAll().stream().map(Admin::getId).collect(Collectors.toSet()));
    }

    // The boot-time rotation of the committed default password (ADR-014) changes a stored admin: a
    // loaded admin, changed and saved again, is updated in place, not inserted a second time.
    @Test
    void GivenSavedAdmin_WhenItsPasswordHashIsChangedAndSavedAgain_ThenTheStoredAdminHasTheNewHash() {
        repo.save(new Admin(1, "admin", "OLD_HASH", true));
        Admin loaded = repo.findById(1);

        loaded.changePasswordHash("NEW_HASH");
        repo.save(loaded);

        Admin reloaded = repo.findById(1);
        assertEquals("NEW_HASH", reloaded.getPasswordHash());
        assertEquals("admin", reloaded.getUsername(), "only the password hash changes");
        assertTrue(reloaded.isDefault(), "only the password hash changes");
        assertEquals(1, repo.findAll().size(), "the admin is updated, not saved a second time");
    }

    // One username, one admin, as for users: every adapter refuses, from save itself, an admin whose
    // username another admin holds. The Jpa adapter flushes the insert so the uk_admins_username
    // violation surfaces there, and re-types it.
    @Test
    void GivenSavedAdmin_WhenAnotherAdminWithTheSameUsernameIsSaved_ThenDuplicateUsernameAndTheFirstIsUnchanged() {
        repo.save(new Admin(1, "admin", "HASH", true));
        Admin sameName = new Admin(2, "admin", "OTHER_HASH", false);

        assertThrows(DuplicateUsernameException.class, () -> repo.save(sameName),
                "a second admin must not be saved under a username another admin holds");

        Admin stored = repo.findByUsername("admin");
        assertEquals(1, stored.getId(), "the first admin keeps the username");
        assertEquals("HASH", stored.getPasswordHash(), "the first admin is unchanged");
        assertTrue(stored.isDefault(), "the first admin is unchanged");
    }

    // IRepository's lock contract: reentrant, and released by one unlock per lock. The second
    // lockForUpdate would hang a non-reentrant lock, hence the timeout on a separate thread. The JPA
    // adapter makes both calls no-ops (@Version guards its writes), so it passes trivially.
    @Test
    @Timeout(value = RepositoryLockProbe.TEST_TIMEOUT_SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void GivenALockTakenTwiceAndReleasedTwice_WhenAnotherThreadLocksTheSameId_ThenItGetsTheLock() throws Exception {
        repo.lockForUpdate(1);
        repo.lockForUpdate(1);
        repo.unlock(1);
        repo.unlock(1);

        RepositoryLockProbe.assertAnotherThreadCanLock(repo, 1);
    }
}
