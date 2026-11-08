package com.ticketing.system.identity.adapter.out.persistence;
import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.ticketing.system.identity.domain.Admin;
import com.ticketing.system.identity.application.port.out.AdminRepository;
import com.ticketing.system.shared.exception.DuplicateUsernameException;
import com.ticketing.system.shared.persistence.ConstraintViolations;
import com.ticketing.system.shared.persistence.PersistenceProfiles;

/**
 * JPA-backed {@link AdminRepository} — active in every run but the in-memory
 * test suite. Adapts the domain port onto Spring Data ({@link SpringDataAdminRepository});
 * the application layer depends only on {@code AdminRepository}, never on Spring Data.
 *
 * <p>{@code lockForUpdate}/{@code unlock} are no-ops: concurrent writes are guarded by
 * {@code Admin}'s {@code @Version} optimistic lock within the surrounding transaction
 * (per the {@code IRepository} contract — JPA replaces the in-memory write-lock with
 * version checks). Admin is written at platform init and by the boot-time password rotation (ADR-014),
 * so contention is nil.
 */
@Repository
@Profile(PersistenceProfiles.JPA_ADAPTERS)
public class JpaAdminRepository implements AdminRepository {

    /** The unique constraint on {@code admins.username}, in the baseline schema (V1). */
    private static final String USERNAME_UNIQUE_CONSTRAINT = "uk_admins_username";

    private final SpringDataAdminRepository data;

    public JpaAdminRepository(SpringDataAdminRepository data) {
        this.data = data;
    }

    @Override
    public void lockForUpdate(Integer id) { /* no-op — @Version optimistic locking */ }

    @Override
    public void unlock(Integer id) { /* no-op */ }

    /**
     * {@inheritDoc}
     *
     * <p>Flushes the write, so a username another admin holds fails here, as
     * {@link DuplicateUsernameException}, rather than at a later flush or at commit, outside the adapter.
     * Any other integrity violation propagates unchanged.
     */
    @Override
    @Transactional
    public void save(Admin admin) {
        try {
            data.saveAndFlush(admin);
        } catch (DataIntegrityViolationException e) {
            if (ConstraintViolations.violates(e, USERNAME_UNIQUE_CONSTRAINT)) {
                throw new DuplicateUsernameException(admin.getUsername());
            }
            throw e;
        }
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
