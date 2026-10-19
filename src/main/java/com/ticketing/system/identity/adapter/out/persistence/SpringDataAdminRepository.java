package com.ticketing.system.identity.adapter.out.persistence;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.ticketing.system.identity.domain.Admin;

/**
 * Spring Data JPA repository for the {@link Admin} aggregate. Auto-implemented by
 * Spring Data and used only by {@link JpaAdminRepository} (the adapter that
 * fulfils the domain {@code AdminRepository} port). Created in every context
 * that has an EntityManagerFactory, which is every run; only the in-memory
 * test suite leaves it unused.
 */
public interface SpringDataAdminRepository extends JpaRepository<Admin, Integer> {

    Optional<Admin> findByUsername(String username);
}
