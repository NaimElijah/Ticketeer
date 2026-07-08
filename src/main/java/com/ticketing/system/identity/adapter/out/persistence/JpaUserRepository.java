package com.ticketing.system.identity.adapter.out.persistence;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.ticketing.system.shared.exception.UserNotFoundException;
import com.ticketing.system.identity.application.port.out.UserRepository;
import com.ticketing.system.identity.domain.User;

/**
 * JPA-backed {@link UserRepository} — active only in the {@code jpa} run/dev profile. Adapts the
 * domain port onto Spring Data ({@link SpringDataUserRepository}); the application layer depends
 * only on {@code UserRepository}, never on Spring Data. Owned {@code companyAppointments} and
 * their {@code permissions} persist by cascade with the user.
 *
 * <p>{@code lockForUpdate}/{@code unlock} are no-ops: concurrency is guarded by {@code User}'s
 * {@code @Version} optimistic lock. {@code save}/{@code updateUser} both delegate to
 * {@code data.save} (insert fresh / update loaded under {@code @Version}); they are
 * {@code @Transactional} so the adapter is self-sufficient before the service layer gains
 * transactions (#359).
 *
 * <p>{@link #nextId()} keeps the assigned-id design (the service mints the id, then constructs the
 * User) but seeds an in-memory counter from the current {@code max(userId)} in the database on
 * first use, so ids don't collide with existing rows after a restart on a persistent database.
 */
@Repository
@Profile("jpa")
public class JpaUserRepository implements UserRepository {

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

    @Override
    @Transactional
    public void save(User user) {
        data.save(user);
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
    public List<User> findUsersWithPendingAppointmentForCompany(int companyId) {
        return data.findUsersWithPendingAppointmentForCompany(companyId);
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
