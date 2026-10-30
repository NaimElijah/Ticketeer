package com.ticketing.system.identity.application.port.out;
import com.ticketing.system.identity.domain.User;

import java.util.List;
import java.util.Optional;

import com.ticketing.system.shared.IRepository;

/** Aggregate-root entry point for the User aggregate. */
public interface UserRepository extends IRepository<User, Integer> {

    /** @throws com.ticketing.system.shared.exception.UserNotFoundException if no user with that id exists */
    User getUserById(int targetId);

    /** Persists changes to an existing User. */
    void updateUser(User targetUser);

    /** By-username lookup. Used by UC-11 (uniqueness check) and UC-12 (login). */
    Optional<User> findByUsername(String username);

    /** By-email lookup. Used by UC-11 as a secondary uniqueness check. */
    Optional<User> findByEmail(String email);

    /** Fast existence check used during UC-11 registration validation. */
    boolean existsByUsername(String username);

    /** All registered users. Backs admin broadcast-to-all-members (messaging II.6.3.2). */
    List<User> findAll();

    /** Mints a fresh userId. Storage owns ID generation rather than the service. UC-11. */
    int nextId();

    /**
     * Persists a newly-registered User, or saves an existing one again. UC-11.
     *
     * <p>The uniqueness checks and the write are one step, so of two concurrent saves of one
     * username, or of one email, exactly one succeeds (business rule §1 #1).
     *
     * @throws com.ticketing.system.shared.exception.DuplicateUsernameException if another user,
     *     one with a different id, already holds {@code user}'s username
     * @throws com.ticketing.system.shared.exception.DuplicateEmailException if another user, one
     *     with a different id, already holds {@code user}'s email; the username is checked first
     */
    void save(User user);

    /** Removes a user by id. Defensive — II.6.2.x is cancelled in v0. */
    void delete(int userId);
}
