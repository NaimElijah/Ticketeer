package com.ticketing.system.organization.adapter.out.persistence;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.ticketing.system.organization.domain.CompanyAppointment;

/**
 * Spring Data JPA repository for {@link CompanyAppointment} — the auto-implemented SQL backing
 * {@link JpaCompanyAppointmentRepository}. The application layer never sees this type; it depends only
 * on the {@code CompanyAppointmentRepository} domain port. The appointment's granted
 * {@code permissions} persist as an {@code @ElementCollection} side-table by cascade with the row.
 */
public interface SpringDataCompanyAppointmentRepository extends JpaRepository<CompanyAppointment, Integer> {

    /** All appointments for the appointed user (queried on the plain {@code target_id} column). */
    List<CompanyAppointment> findByTargetId(int targetId);

    /** The appointed user's appointments in a single company. */
    List<CompanyAppointment> findByTargetIdAndCompanyId(int targetId, int companyId);

    /** Every appointment issued for a company. */
    List<CompanyAppointment> findByCompanyId(int companyId);

    /** Highest existing appointmentId (0 when empty) — seeds the assigned-id sequence across restarts. */
    @Query("select coalesce(max(a.appointmentId), 0) from CompanyAppointment a")
    int findMaxAppointmentId();
}
