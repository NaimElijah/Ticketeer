package com.ticketing.system.identity.adapter.out.persistence;
import com.ticketing.system.identity.application.port.out.UserRepository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.ticketing.system.identity.domain.User;

/**
 * Spring Data JPA repository for {@link User} — the auto-implemented SQL backing
 * {@link JpaUserRepository}. The application layer never sees this type; it depends only on
 * the {@code UserRepository} domain port. Owned {@code companyAppointments} (and their
 * {@code permissions}) persist by cascade with the user.
 */
public interface SpringDataUserRepository extends JpaRepository<User, Integer> {

    Optional<User> findByUsername(String username);

    Optional<User> findByEmail(String email);

    boolean existsByUsername(String username);

    /** Highest existing userId (0 when empty) — seeds the assigned-id sequence so ids survive a restart. */
    @Query("select coalesce(max(u.userId), 0) from User u")
    int findMaxUserId();

    /** Users holding a PENDING appointment in the given company (queries the owned children). */
    @Query("select distinct u from User u join u.companyAppointments a "
            + "where a.companyId = :companyId "
            + "and a.status = com.ticketing.system.Core.Domain.users.AppointmentStatus.PENDING")
    List<User> findUsersWithPendingAppointmentForCompany(@Param("companyId") int companyId);
}
