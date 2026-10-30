package com.ticketing.system.identity.adapter.out.persistence;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.ticketing.system.shared.exception.DuplicateEmailException;
import com.ticketing.system.shared.exception.DuplicateUsernameException;
import com.ticketing.system.shared.exception.UserNotFoundException;
import com.ticketing.system.shared.persistence.ConstraintViolations;
import com.ticketing.system.shared.persistence.PersistenceProfiles;
import com.ticketing.system.identity.application.port.out.UserRepository;
import com.ticketing.system.identity.domain.User;

/**
 * JPA-backed {@link UserRepository} — active in every run but the in-memory test suite. Adapts the
 * domain port onto Spring Data ({@link SpringDataUserRepository}); the application layer depends
 * only on {@code UserRepository}, never on Spring Data. Company appointments are no longer owned by
 * the user (task #20) — they persist through the separate {@code CompanyAppointmentRepository}.
 *
 * <p>{@code lockForUpdate}/{@code unlock} are no-ops: concurrency is guarded by {@code User}'s
 * {@code @Version} optimistic lock. {@code save}/{@code updateUser} both delegate to Spring Data
 * (insert fresh / update loaded under {@code @Version}); {@code save} also flushes, so the
 * {@code uk_users_username} and {@code uk_users_email} constraints are checked inside the adapter.
 * Both are {@code @Transactional} so the adapter is self-sufficient before the service layer gains
 * transactions (#359).
 *
 * <p>{@link #nextId()} keeps the assigned-id design (the service mints the id, then constructs the
 * User) but seeds an in-memory counter from the current {@code max(userId)} in the database on
 * first use, so ids don't collide with existing rows after a restart on a persistent database.
 */
@Repository
@Profile(PersistenceProfiles.JPA_ADAPTERS)
public class JpaUserRepository implements UserRepository {

    /** The unique constraint on {@code users.username}, added by migration V4. */
    private static final String USERNAME_UNIQUE_CONSTRAINT = "uk_users_username";
    /** The unique constraint on {@code users.email}, in the baseline schema (V1). */
    private static final String EMAIL_UNIQUE_CONSTRAINT = "uk_users_email";

    private final SpringDataUserRepository data;
    private final AtomicInteger idSequence = new AtomicInteger(0);
    private volatile boolean seeded = false;

    public JpaUserRepository(SpringDataUserRepository data) {
        this.data = data;
    }

    @Override
    public void lockForUpdate(Integer id) { /* no-op — @Version optimistic locking */ }

    @Override
    public void unlock(Integer id) { /* no-op */ }

    @Override
    public int nextId() {
        ensureSeeded();
        return idSequence.incrementAndGet();
    }

    private void ensureSeeded() {
        if (!seeded) {
            synchronized (this) {
                if (!seeded) {
                    idSequence.set(data.findMaxUserId());
                    seeded = true;
                }
            }
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Flushes the insert, so a username or an email another user holds fails here, as
     * {@link DuplicateUsernameException} or {@link DuplicateEmailException}, rather than at a later
     * flush or at commit, outside the adapter. Any other integrity violation propagates unchanged.
     */
    @Override
    @Transactional
    public void save(User user) {
        try {
            data.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            if (ConstraintViolations.violates(e, USERNAME_UNIQUE_CONSTRAINT)) {
                throw new DuplicateUsernameException(user.getUsername());
            }
            if (ConstraintViolations.violates(e, EMAIL_UNIQUE_CONSTRAINT)) {
                throw new DuplicateEmailException();
            }
            throw e;
        }
    }

    @Override
    @Transactional
    public void updateUser(User targetUser) {
        data.save(targetUser);
    }

    @Override
    public User getUserById(int targetId) {
        return data.findById(targetId).orElseThrow(() -> new UserNotFoundException(targetId));
    }

    @Override
    public Optional<User> findByUsername(String username) {
        return data.findByUsername(username);
    }

    @Override
    public Optional<User> findByEmail(String email) {
        return data.findByEmail(email);
    }

    @Override
    public boolean existsByUsername(String username) {
        return data.existsByUsername(username);
    }

    @Override
    public List<User> findAll() {
        return data.findAll();
    }

    @Override
    @Transactional
    public void delete(int userId) {
        // Idempotent: deleteById would throw for an unknown id; the Memory impl is a silent no-op.
        if (data.existsById(userId)) {
            data.deleteById(userId);
        }
    }
}
