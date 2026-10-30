package com.ticketing.system.unit.infrastructure.persistence.UserPersistence;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.shared.exception.DuplicateEmailException;
import com.ticketing.system.shared.exception.DuplicateUsernameException;
import com.ticketing.system.shared.exception.UserNotFoundException;
import com.ticketing.system.identity.application.port.out.UserRepository;
import com.ticketing.system.identity.domain.User;

// Contract tests every UserRepository implementation must satisfy. Future JPA-backed
// adapter will subclass this with its own newRepository() factory; tests are reused.
abstract class IUserRepositoryContractTest {

    protected abstract UserRepository newRepository();

    private UserRepository repo;

    @BeforeEach
    void setUp() {
        repo = newRepository();
    }

    @Test
    void save_thenFindByUsername_returnsTheSavedUser() {
        int id = repo.nextId();
        User user = new User(id, "alice", "alice@example.com", "HASH",20);
        repo.save(user);

        Optional<User> found = repo.findByUsername("alice");
        assertTrue(found.isPresent());
        assertEquals(id, found.get().getUserId());
        assertEquals("alice@example.com", found.get().getEmail());
    }

    @Test
    void save_thenFindByEmail_returnsTheSavedUser() {
        int id = repo.nextId();
        User user = new User(id, "alice", "alice@example.com", "HASH",2);
        repo.save(user);

        Optional<User> found = repo.findByEmail("alice@example.com");
        assertTrue(found.isPresent());
        assertEquals("alice", found.get().getUsername());
    }

    @Test
    void findByUsername_returnsEmptyWhenMissing() {
        assertFalse(repo.findByUsername("ghost").isPresent());
    }

    @Test
    void findByEmail_returnsEmptyWhenMissing() {
        assertFalse(repo.findByEmail("ghost@example.com").isPresent());
    }

    @Test
    void existsByUsername_falseWhenMissingTrueWhenSaved() {
        assertFalse(repo.existsByUsername("alice"));
        repo.save(new User(repo.nextId(), "alice", "alice@example.com", "HASH",15));
        assertTrue(repo.existsByUsername("alice"));
    }

    @Test
    void nextId_producesDistinctIncreasingValues() {
        int a = repo.nextId();
        int b = repo.nextId();
        int c = repo.nextId();
        assertNotEquals(a, b);
        assertNotEquals(b, c);
        assertTrue(b > a);
        assertTrue(c > b);
    }

    @Test
    void getUserById_returnsTheSavedUser() {
        int id = repo.nextId();
        User user = new User(id, "alice", "alice@example.com", "HASH",15);
        repo.save(user);

        User found = repo.getUserById(id);
        assertEquals("alice", found.getUsername());
    }

    @Test
    void getUserById_throwsWhenMissing() {
        assertThrows(UserNotFoundException.class, () -> repo.getUserById(9999));
    }

    @Test
    void delete_removesTheUser() {
        int id = repo.nextId();
        repo.save(new User(id, "alice", "alice@example.com", "HASH",15));
        assertTrue(repo.existsByUsername("alice"));

        repo.delete(id);
        assertFalse(repo.existsByUsername("alice"));
    }

    // ---- one username, one user (#185) ----
    // Two registrations that both pass register's existsByUsername check must not both create the
    // name. Every adapter refuses, from save itself, a user whose username another user already
    // holds: the Jpa adapter flushes the insert so the uk_users_username violation surfaces there,
    // and re-types it, rather than at a later flush or at commit, outside the adapter.

    @Test
    void GivenSavedUser_WhenAnotherUserWithTheSameUsernameIsSaved_ThenDuplicateUsernameException() {
        repo.save(new User(repo.nextId(), "alice", "alice@example.com", "HASH", 20));
        User sameName = new User(repo.nextId(), "alice", "other-alice@example.com", "HASH", 30);

        assertThrows(DuplicateUsernameException.class, () -> repo.save(sameName),
                "a second user must not be saved under a username another user holds");
    }

    // One email, one user, held the same way: two registrations that both pass register's email check
    // must not both create an account with it, and the loser gets the same answer the check gives.
    @Test
    void GivenSavedUser_WhenAnotherUserWithTheSameEmailIsSaved_ThenDuplicateEmailException() {
        repo.save(new User(repo.nextId(), "alice", "alice@example.com", "HASH", 20));
        User sameEmail = new User(repo.nextId(), "alicia", "alice@example.com", "HASH", 30);

        assertThrows(DuplicateEmailException.class, () -> repo.save(sameEmail),
                "a second user must not be saved under an email another user holds");
    }

    @Test
    void GivenSavedUser_WhenTheSameUserIsSavedAgain_ThenSaved() {
        User alice = new User(repo.nextId(), "alice", "alice@example.com", "HASH", 20);
        repo.save(alice);

        assertDoesNotThrow(() -> repo.save(alice), "saving a user again keeps its own username");
        assertTrue(repo.existsByUsername("alice"));
    }
}
