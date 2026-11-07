package com.ticketing.system.unit.infrastructure.persistence.UserPersistence;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

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
    void GivenSavedUser_WhenFindByUsername_ThenReturnsTheSavedUser() {
        int id = repo.nextId();
        User user = new User(id, "alice", "alice@example.com", "HASH",20);
        repo.save(user);

        Optional<User> found = repo.findByUsername("alice");
        assertTrue(found.isPresent());
        assertEquals(id, found.get().getUserId());
        assertEquals("alice@example.com", found.get().getEmail());
    }

    @Test
    void GivenSavedUser_WhenFindByEmail_ThenReturnsTheSavedUser() {
        int id = repo.nextId();
        User user = new User(id, "alice", "alice@example.com", "HASH",2);
        repo.save(user);

        Optional<User> found = repo.findByEmail("alice@example.com");
        assertTrue(found.isPresent());
        assertEquals("alice", found.get().getUsername());
    }

    @Test
    void GivenNoSuchUser_WhenFindByUsername_ThenReturnsEmpty() {
        assertFalse(repo.findByUsername("ghost").isPresent());
    }

    @Test
    void GivenNoSuchUser_WhenFindByEmail_ThenReturnsEmpty() {
        assertFalse(repo.findByEmail("ghost@example.com").isPresent());
    }

    @Test
    void GivenARepositoryBeforeAndAfterASave_WhenExistsByUsername_ThenFalseWhenMissingTrueWhenSaved() {
        assertFalse(repo.existsByUsername("alice"));
        repo.save(new User(repo.nextId(), "alice", "alice@example.com", "HASH",15));
        assertTrue(repo.existsByUsername("alice"));
    }

    @Test
    void GivenRepository_WhenNextIdCalledThreeTimes_ThenProducesDistinctIncreasingValues() {
        int a = repo.nextId();
        int b = repo.nextId();
        int c = repo.nextId();
        assertNotEquals(a, b);
        assertNotEquals(b, c);
        assertTrue(b > a);
        assertTrue(c > b);
    }

    @Test
    void GivenSavedUser_WhenGetUserById_ThenReturnsTheSavedUser() {
        int id = repo.nextId();
        User user = new User(id, "alice", "alice@example.com", "HASH",15);
        repo.save(user);

        User found = repo.getUserById(id);
        assertEquals("alice", found.getUsername());
    }

    @Test
    void GivenNoSuchUser_WhenGetUserById_ThenThrowsUserNotFound() {
        assertThrows(UserNotFoundException.class, () -> repo.getUserById(9999));
    }

    // Backs the admin's broadcast to every member (II.6.3.2).
    @Test
    void GivenTwoSavedUsers_WhenFindAll_ThenReturnsBoth() {
        int alice = repo.nextId();
        repo.save(new User(alice, "alice", "alice@example.com", "HASH", 20));
        int bob = repo.nextId();
        repo.save(new User(bob, "bob", "bob@example.com", "HASH", 30));

        assertEquals(Set.of(alice, bob), repo.findAll().stream().map(User::getUserId).collect(Collectors.toSet()));
    }

    @Test
    void GivenNoUsers_WhenFindAll_ThenReturnsEmpty() {
        assertTrue(repo.findAll().isEmpty());
    }

    @Test
    void GivenSavedUser_WhenDelete_ThenRemovesTheUser() {
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
