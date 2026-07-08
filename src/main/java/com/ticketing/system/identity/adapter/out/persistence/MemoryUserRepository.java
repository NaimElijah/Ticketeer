package com.ticketing.system.identity.adapter.out.persistence;

import com.ticketing.system.Infrastructure.persistence.RepositoryLocks;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import com.ticketing.system.shared.exception.UserNotFoundException;
import com.ticketing.system.identity.application.port.out.UserRepository;
import com.ticketing.system.identity.domain.User;

/**
 * In-memory {@link UserRepository}.
 *
 * <p>Storage is a thread-safe {@code ConcurrentHashMap}; IDs are minted from
 * an {@code AtomicInteger} starting at 1. {@code @Profile("!jpa")}: the {@code jpa}
 * run/dev profile swaps in {@link JpaUserRepository} instead.
 */
@Repository
@Profile("!jpa")
public class MemoryUserRepository implements UserRepository {

    private final Map<Integer, User> usersById = new ConcurrentHashMap<>();
    private final AtomicInteger idSequence = new AtomicInteger(1);
    private final RepositoryLocks<Integer> locks = new RepositoryLocks<>();

    @Override
    public void lockForUpdate(Integer id) { locks.lock(id); }

    @Override
    public void unlock(Integer id) { locks.unlock(id); }

    @Override
    public List<User> findAll() {
        return new java.util.ArrayList<>(usersById.values());
    }

    @Override
    public int nextId() {
        return idSequence.getAndIncrement();
    }

    @Override
    public User getUserById(int targetId) {
        User user = usersById.get(targetId);
        if (user == null) {
            throw new UserNotFoundException(targetId);
        }
        return user;
    }

    @Override
    public void updateUser(User targetUser) {
        usersById.put(targetUser.getUserId(), targetUser);
    }

    @Override
    public Optional<User> findByUsername(String username) {
        return usersById.values().stream()
                .filter(u -> username.equals(u.getUsername()))
                .findFirst();
    }

    @Override
    public List<User> findUsersWithPendingAppointmentForCompany(int companyId) {
        return usersById.values().stream()
                .filter(u -> u.getPendingCompanyAppointment(companyId) != null)
                .toList();
    }

    @Override
    public Optional<User> findByEmail(String email) {
        return usersById.values().stream()
                .filter(u -> email.equals(u.getEmail()))
                .findFirst();
    }

    @Override
    public boolean existsByUsername(String username) {
        return findByUsername(username).isPresent();
    }

    @Override
    public void save(User user) {
        usersById.put(user.getUserId(), user);
    }

    @Override
    public void delete(int userId) {
        usersById.remove(userId);
    }
}
