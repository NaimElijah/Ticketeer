package com.ticketing.system.identity.adapter.out.persistence;

import com.ticketing.system.shared.persistence.RepositoryLocks;
import com.ticketing.system.shared.persistence.PersistenceProfiles;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import com.ticketing.system.identity.domain.Admin;
import com.ticketing.system.identity.application.port.out.AdminRepository;
import com.ticketing.system.shared.exception.DuplicateUsernameException;

/**
 * In-memory {@link AdminRepository}, used only by the in-memory test suite
 * ({@code test} with {@code memory}, ADR-005); every other run gets {@link JpaAdminRepository}.
 */
@Repository
@Profile(PersistenceProfiles.IN_MEMORY_ADAPTERS)
public class MemoryAdminRepository implements AdminRepository {

    private final Map<Integer, Admin> adminsById = new ConcurrentHashMap<>();
    private final RepositoryLocks<Integer> locks = new RepositoryLocks<>();

    @Override
    public void lockForUpdate(Integer id) { locks.lock(id); }

    @Override
    public void unlock(Integer id) { locks.unlock(id); }

    /**
     * {@inheritDoc}
     *
     * <p>No database constraint stands behind this adapter, so saves are serialised: the username check
     * and the write are one step, as {@code uk_admins_username} makes them for the Jpa adapter.
     */
    @Override
    public synchronized void save(Admin admin) {
        boolean usernameTakenByAnother = adminsById.values().stream()
                .anyMatch(a -> a.getId() != admin.getId() && a.getUsername().equals(admin.getUsername()));
        if (usernameTakenByAnother) {
            throw new DuplicateUsernameException(admin.getUsername());
        }
        adminsById.put(admin.getId(), admin);
    }

    @Override
    public Admin findById(int adminId) {
        return adminsById.get(adminId);
    }

    @Override
    public Admin findByUsername(String username) {
        if (username == null) return null;
        for (Admin a : adminsById.values()) {
            if (username.equals(a.getUsername())) return a;
        }
        return null;
    }

    @Override
    public boolean existsAny() {
        return !adminsById.isEmpty();
    }

    @Override
    public List<Admin> findAll() {
        return new ArrayList<>(adminsById.values());
    }
}
