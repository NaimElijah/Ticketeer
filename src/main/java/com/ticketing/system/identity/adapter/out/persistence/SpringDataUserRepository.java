package com.ticketing.system.identity.adapter.out.persistence;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.ticketing.system.identity.domain.User;

/**
 * Spring Data JPA repository for {@link User} — the auto-implemented SQL backing
 * {@link JpaUserRepository}. The application layer never sees this type; it depends only on
 * the {@code UserRepository} domain port. Company appointments are no longer owned by the user
 * (task #20); they persist through the separate {@code SpringDataCompanyAppointmentRepository}.
 */
public interface SpringDataUserRepository extends JpaRepository<User, Integer> {

    Optional<User> findByUsername(String username);

    Optional<User> findByEmail(String email);

    boolean existsByUsername(String username);

    /** Highest existing userId (0 when empty) — seeds the assigned-id sequence so ids survive a restart. */
    @Query("select coalesce(max(u.userId), 0) from User u")
    int findMaxUserId();
}
