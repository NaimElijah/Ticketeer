package com.ticketing.system.organization.application.port.out;
import com.ticketing.system.organization.domain.ProductionCompany;
import com.ticketing.system.identity.domain.Admin;

import java.util.List;
import java.util.Optional;

import com.ticketing.system.shared.IRepository;

// Aggregate-root entry point for the ProductionCompany aggregate.
public interface ProductionCompanyRepository extends IRepository<ProductionCompany, Integer> {

    ProductionCompany getCompanyById(int companyId);

    void updateCompany(ProductionCompany company);

    // UC-18 — uniqueness check at registration.
    Optional<ProductionCompany> findByName(String name);

    boolean existsByName(String name);

    // UC-3 / UC-7 — public catalog excludes closed/suspended companies.
    List<ProductionCompany> findActive();

    // Owner's own list of companies they founded (informational).
    List<ProductionCompany> findByFounder(int founderUserId);

    // Admin view — every company in the system regardless of status.
    List<ProductionCompany> findAll();

    // UC-18 — persist newly-registered company.
    void save(ProductionCompany company);

    // UC-18- genrate company id
    int nextId();
}
