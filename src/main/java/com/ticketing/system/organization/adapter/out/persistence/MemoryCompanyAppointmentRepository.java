package com.ticketing.system.organization.adapter.out.persistence;

import com.ticketing.system.shared.persistence.RepositoryLocks;
import com.ticketing.system.shared.persistence.PersistenceProfiles;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import com.ticketing.system.organization.application.port.out.CompanyAppointmentRepository;
import com.ticketing.system.organization.domain.CompanyAppointment;

/**
 * In-memory {@link CompanyAppointmentRepository}. Lets Spring wire {@code CompanyMembershipService}
 * (and, through it, the organization / catalog / messaging / reporting services) without a database.
 * Used only by the in-memory test suite ({@code test} with {@code memory}); every other run gets
 * {@link JpaCompanyAppointmentRepository}. Storage is a thread-safe {@code ConcurrentHashMap}
 * keyed by appointmentId; ids are minted from an {@code AtomicInteger} starting at 1, mirroring the
 * sibling Memory repositories.
 */
@Repository
@Profile(PersistenceProfiles.IN_MEMORY_ADAPTERS)
public class MemoryCompanyAppointmentRepository implements CompanyAppointmentRepository {

    // Appointments keyed by their assigned id.
    private final Map<Integer, CompanyAppointment> appointmentsById = new ConcurrentHashMap<>();
    // Assigned-id sequence (the service calls nextId() before constructing an appointment).
    private final AtomicInteger idSequence = new AtomicInteger(1);
    // Per-id write locks; the Jpa adapter relies on @Version instead.
    private final RepositoryLocks<Integer> locks = new RepositoryLocks<>();

    @Override
    public void lockForUpdate(Integer id) {
        locks.lock(id);
    }

    @Override
    public void unlock(Integer id) {
        locks.unlock(id);
    }

    @Override
    public int nextId() {
        return idSequence.getAndIncrement();
    }

    @Override
    public CompanyAppointment save(CompanyAppointment appointment) {
        appointmentsById.put(appointment.getAppointmentId(), appointment);
        return appointment;
    }

    @Override
    public Optional<CompanyAppointment> findById(int appointmentId) {
        return Optional.ofNullable(appointmentsById.get(appointmentId));
    }

    @Override
    public List<CompanyAppointment> findByTargetId(int userId) {
        List<CompanyAppointment> result = new ArrayList<>();
        for (CompanyAppointment a : appointmentsById.values()) {
            if (a.getTargetId() == userId) {
                result.add(a);
            }
        }
        return result;
    }

    @Override
    public List<CompanyAppointment> findByTargetIdAndCompanyId(int userId, int companyId) {
        List<CompanyAppointment> result = new ArrayList<>();
        for (CompanyAppointment a : appointmentsById.values()) {
            if (a.getTargetId() == userId && a.getCompanyId() == companyId) {
                result.add(a);
            }
        }
        return result;
    }

    @Override
    public List<CompanyAppointment> findByCompanyId(int companyId) {
        List<CompanyAppointment> result = new ArrayList<>();
        for (CompanyAppointment a : appointmentsById.values()) {
            if (a.getCompanyId() == companyId) {
                result.add(a);
            }
        }
        return result;
    }

    @Override
    public void delete(CompanyAppointment appointment) {
        appointmentsById.remove(appointment.getAppointmentId());
    }
}
