package com.ticketing.system.organization.application.port.out;

import java.util.List;
import java.util.Optional;

import com.ticketing.system.organization.domain.CompanyAppointment;
import com.ticketing.system.shared.IRepository;

/**
 * Aggregate-root entry point for the {@link CompanyAppointment} aggregate (task #20).
 *
 * <p>Promoting {@code CompanyAppointment} off the {@code User} aggregate onto its own repository is
 * what breaks the last {@code identity -> organization} cycle: appointments are now looked up by the
 * appointed user's id ({@code targetId}) through this port instead of being an owned {@code @OneToMany}
 * collection on {@code User}. Two adapters implement it, selected by Spring profile — a
 * {@code ConcurrentHashMap}-backed Memory one ({@code @Profile("!jpa")}) and a Spring-Data-backed JPA
 * one ({@code @Profile("jpa")}); the application layer depends only on this port.
 */
public interface CompanyAppointmentRepository extends IRepository<CompanyAppointment, Integer> {

    /** Persists a new or modified appointment and returns the stored instance. */
    CompanyAppointment save(CompanyAppointment appointment);

    /** Look up a single appointment by its id. */
    Optional<CompanyAppointment> findById(int appointmentId);

    /** Every appointment held by the given user (the appointed {@code targetId}), any status/company. */
    List<CompanyAppointment> findByTargetId(int userId);

    /** Every appointment held by the given user in one company (usually 0, 1 or 2 — pending + active). */
    List<CompanyAppointment> findByTargetIdAndCompanyId(int userId, int companyId);

    /** Every appointment issued for the given company, any target/status. Backs the company rosters. */
    List<CompanyAppointment> findByCompanyId(int companyId);

    /** Removes an appointment (defensive — status transitions are preferred over deletion). */
    void delete(CompanyAppointment appointment);

    /** Mints a fresh appointmentId. Storage owns id generation rather than the service. */
    int nextId();
}
