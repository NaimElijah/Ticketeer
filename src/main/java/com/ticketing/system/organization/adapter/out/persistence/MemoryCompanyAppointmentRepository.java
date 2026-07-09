package com.ticketing.system.organization.adapter.out.persistence;

import com.ticketing.system.shared.persistence.RepositoryLocks;
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
 * {@code @Profile("!jpa")}: the {@code jpa} run/dev profile swaps in
 * {@link JpaCompanyAppointmentRepository} instead. Storage is a thread-safe {@code ConcurrentHashMap}
 * keyed by appointmentId; ids are minted from an {@code AtomicInteger} starting at 1, mirroring the
 * sibling Memory repositories.
 */
@Repository
@Profile("!jpa")
public class MemoryCompanyAppointmentRepository implements CompanyAppointmentRepository {

    // Appointments keyed by their assigned id.
    private final Map<Integer, CompanyAppointment> appointmentsById = new ConcurrentHashMap<>();
    // Assigned-id sequence (the service calls nextId() before constructing an appointment).
    private final AtomicInteger idSequence = new AtomicInteger(1);
    // Per-id write locks (no-op equivalent under jpa, where @Version handles concurrency).
    private final RepositoryLocks<Integer> locks = new RepositoryLocks<>();

    @Override
    public void lockForUpdate(Integer id) {
        locks.lock(id); // acquire the per-id write lock
    }

    @Override
    public void unlock(Integer id) {
        locks.unlock(id); // release the per-id write lock
    }

    @Override
    public int nextId() {
        return idSequence.getAndIncrement(); // hand out a fresh, monotonically increasing id
    }

    @Override
    public CompanyAppointment save(CompanyAppointment appointment) {
        appointmentsById.put(appointment.getAppointmentId(), appointment); // store/replace by id
        return appointment; // the stored instance is the saved one
    }

    @Override
    public Optional<CompanyAppointment> findById(int appointmentId) {
        return Optional.ofNullable(appointmentsById.get(appointmentId)); // present iff stored
    }

    @Override
    public List<CompanyAppointment> findByTargetId(int userId) {
        List<CompanyAppointment> result = new ArrayList<>(); // appointments held by this user
        for (CompanyAppointment a : appointmentsById.values()) {
            if (a.getTargetId() == userId) {
                result.add(a);
            }
        }
        return result;
    }

    @Override
    public List<CompanyAppointment> findByTargetIdAndCompanyId(int userId, int companyId) {
        List<CompanyAppointment> result = new ArrayList<>(); // this user's appointments in one company
        for (CompanyAppointment a : appointmentsById.values()) {
            if (a.getTargetId() == userId && a.getCompanyId() == companyId) {
                result.add(a);
            }
        }
        return result;
    }

    @Override
    public List<CompanyAppointment> findByCompanyId(int companyId) {
        List<CompanyAppointment> result = new ArrayList<>(); // every appointment issued for this company
        for (CompanyAppointment a : appointmentsById.values()) {
            if (a.getCompanyId() == companyId) {
                result.add(a);
            }
        }
        return result;
    }

    @Override
    public void delete(CompanyAppointment appointment) {
        appointmentsById.remove(appointment.getAppointmentId()); // remove by id (idempotent)
    }
}
