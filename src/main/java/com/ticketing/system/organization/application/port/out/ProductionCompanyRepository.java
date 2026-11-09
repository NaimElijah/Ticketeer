package com.ticketing.system.organization.application.port.out;
import com.ticketing.system.organization.domain.ProductionCompany;

import java.util.List;
import java.util.Optional;

import com.ticketing.system.shared.IRepository;

/**
 * Aggregate-root entry point for the {@link ProductionCompany} aggregate.
 */
public interface ProductionCompanyRepository extends IRepository<ProductionCompany, Integer> {

    /**
     * Returns the company with the given id.
     *
     * @param companyId the company's id
     * @return the company; never {@code null}
     * @throws com.ticketing.system.shared.exception.CompanyNotFoundException if there is no such company
     */
    ProductionCompany getCompanyById(int companyId);

    /**
     * Stores the changes made to an existing company.
     */
    void updateCompany(ProductionCompany company);

    /**
     * Finds the company with exactly this name.
     *
     * @param name the company name; {@code null} matches nothing
     * @return the company, or empty if there is none
     */
    Optional<ProductionCompany> findByName(String name);

    /**
     * UC-18 — returns whether a company with exactly this name exists, for the uniqueness check at
     * registration; {@code null} matches nothing.
     */
    boolean existsByName(String name);

    /**
     * UC-3 / UC-7 — returns the companies whose status is {@code CompanyStatus.ACTIVE}, which the public catalog
     * lists.
     */
    List<ProductionCompany> findActive();

    /**
     * Returns the companies the given user founded.
     */
    List<ProductionCompany> findByFounder(int founderUserId);

    /**
     * Admin view — returns every company in the system regardless of status, in no guaranteed order.
     */
    List<ProductionCompany> findAll();

    /**
     * Stores a company, new or existing. UC-18 registers a company through it.
     */
    void save(ProductionCompany company);

    /**
     * UC-18 — mints the id of a company about to be registered. The id is unique among those this instance has
     * minted; on the Jpa adapter it is also above the ids of the rows present when the instance first seeded.
     */
    int nextId();
}
