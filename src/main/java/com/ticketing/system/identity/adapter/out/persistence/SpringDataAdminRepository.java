package com.ticketing.system.identity.adapter.out.persistence;
import com.ticketing.system.identity.application.port.out.AdminRepository;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.ticketing.system.identity.domain.Admin;

/**
 * Spring Data JPA repository for the {@link Admin} aggregate. Auto-implemented by
 * Spring Data and used only by {@link JpaAdminRepository} (the adapter that
 * fulfils the domain {@code AdminRepository} port). Only scanned when JPA is
 * active (an EntityManagerFactory exists), i.e. the {@code jpa} run profile and
 * the JPA contract test; never in the dev profile (JPA autoconfig excluded).
 */
public interface SpringDataAdminRepository extends JpaRepository<Admin, Integer> {

    Optional<Admin> findByUsername(String username);
}
