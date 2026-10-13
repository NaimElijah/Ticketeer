package com.ticketing.system.identity.adapter.out.persistence;
import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import com.ticketing.system.identity.domain.Admin;
import com.ticketing.system.identity.application.port.out.AdminRepository;

/**
 * JPA-backed {@link AdminRepository} — active only in the {@code jpa} run/dev
 * profile. Adapts the domain port onto Spring Data ({@link SpringDataAdminRepository});
 * the application layer depends only on {@code AdminRepository}, never on Spring Data.
 *
 * <p>{@code lockForUpdate}/{@code unlock} are no-ops: concurrent writes are guarded by
 * {@code Admin}'s {@code @Version} optimistic lock within the surrounding transaction
 * (per the {@code IRepository} contract — JPA replaces the in-memory write-lock with
 * version checks). Admin is written once at platform init, so contention is nil.
 */
@Repository
@Profile("jpa")
public class JpaAdminRepository implements AdminRepository {

    private final SpringDataAdminRepository data;

    public JpaAdminRepository(SpringDataAdminRepository data) {
        this.data = data;
    }

    @Override
    public void lockForUpdate(Integer id) { /* no-op — @Version optimistic locking */ }

    @Override
    public void unlock(Integer id) { /* no-op */ }

    @Override
    public void save(Admin admin) {
        data.save(admin);
    }

    @Override
    public Admin findById(int adminId) {
        return data.findById(adminId).orElse(null);
    }

    @Override
    public Admin findByUsername(String username) {
        if (username == null) {
            return null;
        }
        return data.findByUsername(username).orElse(null);
    }

    @Override
    public boolean existsAny() {
        return data.count() > 0;
    }

    @Override
    public List<Admin> findAll() {
        return data.findAll();
    }
}
