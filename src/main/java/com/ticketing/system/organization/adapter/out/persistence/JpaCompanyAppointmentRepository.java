package com.ticketing.system.organization.adapter.out.persistence;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.ticketing.system.organization.application.port.out.CompanyAppointmentRepository;
import com.ticketing.system.organization.domain.CompanyAppointment;

/**
 * JPA-backed {@link CompanyAppointmentRepository} — active only in the {@code jpa} run/dev profile.
 * Adapts the domain port onto Spring Data ({@link SpringDataCompanyAppointmentRepository}); the
 * application layer depends only on {@code CompanyAppointmentRepository}, never on Spring Data. The
 * appointment's {@code permissions} persist as an {@code @ElementCollection} side-table by cascade.
 *
 * <p>{@code lockForUpdate}/{@code unlock} are no-ops (concurrency via the entity's {@code @Version}).
 * {@code save}/{@code delete} are {@code @Transactional} so the adapter is self-sufficient. Since the
 * id is ASSIGNED (not {@code @GeneratedValue}), {@link #nextId()} seeds an in-memory counter from
 * {@code max(appointmentId)} on first use so ids survive a restart on a persistent database — matching
 * {@link JpaProductionCompanyRepository}.
 */
@Repository
@Profile("jpa")
public class JpaCompanyAppointmentRepository implements CompanyAppointmentRepository {

    private final SpringDataCompanyAppointmentRepository data;
    private final AtomicInteger idSequence = new AtomicInteger(0);
    private volatile boolean seeded = false;

    public JpaCompanyAppointmentRepository(SpringDataCompanyAppointmentRepository data) {
        this.data = data; // Spring Data delegate
    }

    @Override
    public void lockForUpdate(Integer id) { /* no-op — @Version optimistic locking */ }

    @Override
    public void unlock(Integer id) { /* no-op */ }

    @Override
    @Transactional
    public CompanyAppointment save(CompanyAppointment appointment) {
        return data.save(appointment); // INSERT when version is null, UPDATE for a loaded row
    }

    @Override
    public Optional<CompanyAppointment> findById(int appointmentId) {
        return data.findById(appointmentId); // Optional by primary key
    }

    @Override
    public List<CompanyAppointment> findByTargetId(int userId) {
        return data.findByTargetId(userId); // derived query on target_id
    }

    @Override
    public List<CompanyAppointment> findByTargetIdAndCompanyId(int userId, int companyId) {
        return data.findByTargetIdAndCompanyId(userId, companyId); // derived query on target_id + company_id
    }

    @Override
    public List<CompanyAppointment> findByCompanyId(int companyId) {
        return data.findByCompanyId(companyId); // derived query on company_id
    }

    @Override
    @Transactional
    public void delete(CompanyAppointment appointment) {
        data.delete(appointment); // DELETE by entity
    }

    @Override
    public int nextId() {
        ensureSeeded();
        return idSequence.incrementAndGet(); // fresh id above any existing row
    }

    // Lazily seed the id sequence from the current max in the database (once), so assigned ids never
    // collide with existing rows after a restart on a persistent database.
    private void ensureSeeded() {
        if (!seeded) {
            synchronized (this) {
                if (!seeded) {
                    idSequence.set(data.findMaxAppointmentId());
                    seeded = true;
                }
            }
        }
    }
}
