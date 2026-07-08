package com.ticketing.system.organization.adapter.out.persistence;
import com.ticketing.system.organization.application.port.out.ProductionCompanyRepository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.ticketing.system.organization.domain.CompanyStatus;
import com.ticketing.system.organization.domain.ProductionCompany;

/**
 * Spring Data JPA repository for {@link ProductionCompany} — the auto-implemented SQL backing
 * {@link JpaProductionCompanyRepository}. The application layer never sees this type; it depends
 * only on the {@code ProductionCompanyRepository} domain port. Owner/manager id lists persist as
 * {@code @ElementCollection} side-tables and the purchase policy as a JSON column, all by cascade
 * with the company.
 */
public interface SpringDataProductionCompanyRepository extends JpaRepository<ProductionCompany, Integer> {

    Optional<ProductionCompany> findByName(String name);

    boolean existsByName(String name);

    List<ProductionCompany> findByCompanyStatus(CompanyStatus status);

    List<ProductionCompany> findByFounderId(int founderId);

    /** Highest existing companyId (0 when empty) — seeds the assigned-id sequence across restarts. */
    @Query("select coalesce(max(c.companyId), 0) from ProductionCompany c")
    int findMaxCompanyId();
}
