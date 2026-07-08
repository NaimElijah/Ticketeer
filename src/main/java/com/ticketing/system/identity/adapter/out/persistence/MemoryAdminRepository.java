package com.ticketing.system.identity.adapter.out.persistence;
import com.ticketing.system.governance.application.service.SystemAdminService;

import com.ticketing.system.Infrastructure.persistence.RepositoryLocks;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import com.ticketing.system.identity.domain.Admin;
import com.ticketing.system.identity.application.port.out.AdminRepository;

/**
 * In-memory {@link AdminRepository} for V1.
 *
 * <p>Lets Spring wire SystemAdminService. A future JPA-backed adapter
 * replaces this class without touching the application layer.
 */
@Repository
@Profile("!jpa")
public class MemoryAdminRepository implements AdminRepository {

    private final Map<Integer, Admin> adminsById = new ConcurrentHashMap<>();
    private final RepositoryLocks<Integer> locks = new RepositoryLocks<>();

    @Override
    public void lockForUpdate(Integer id) { locks.lock(id); }

    @Override
    public void unlock(Integer id) { locks.unlock(id); }

    @Override
    public void save(Admin admin) {
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
