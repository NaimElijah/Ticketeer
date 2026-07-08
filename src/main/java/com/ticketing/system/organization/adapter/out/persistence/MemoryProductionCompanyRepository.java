package com.ticketing.system.organization.adapter.out.persistence;
import com.ticketing.system.catalog.application.service.CatalogService;
import com.ticketing.system.catalog.application.service.EventManagementService;
import com.ticketing.system.organization.application.service.CompanyManagementService;

import com.ticketing.system.shared.persistence.RepositoryLocks;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import com.ticketing.system.organization.domain.CompanyStatus;
import com.ticketing.system.organization.application.port.out.ProductionCompanyRepository;
import com.ticketing.system.organization.domain.ProductionCompany;

/**
 * In-memory {@link ProductionCompanyRepository}. Lets Spring wire
 * CatalogService / CompanyManagementService / EventManagementService.
 * {@code @Profile("!jpa")}: the {@code jpa} run/dev profile swaps in
 * {@link JpaProductionCompanyRepository} instead.
 */
@Repository
@Profile("!jpa")
public class MemoryProductionCompanyRepository implements ProductionCompanyRepository {

    private final Map<Integer, ProductionCompany> companiesById = new ConcurrentHashMap<>();
    private final AtomicInteger idSequence = new AtomicInteger(1);
    private final RepositoryLocks<Integer> locks = new RepositoryLocks<>();

    @Override
    public void lockForUpdate(Integer id) {
        locks.lock(id);
    }

    @Override
    public void unlock(Integer id) {
        locks.unlock(id);
    }

    @Override
    public void save(ProductionCompany company) {
        companiesById.put(company.getCompanyId(), company);
    }

    @Override
    public void updateCompany(ProductionCompany company) {
        companiesById.put(company.getCompanyId(), company);
    }

    @Override
    public ProductionCompany getCompanyById(int companyId) {
        if (!companiesById.containsKey(companyId)) {
            throw new RuntimeException("Company with ID " + companyId + " not found");
        }
        return companiesById.get(companyId);
    }

    @Override
    public Optional<ProductionCompany> findByName(String name) {
        if (name == null)
            return Optional.empty();
        for (ProductionCompany c : companiesById.values()) {
            if (name.equals(c.getName()))
                return Optional.of(c);
        }
        return Optional.empty();
    }

    @Override
    public boolean existsByName(String name) {
        return findByName(name).isPresent();
    }

    @Override
    public List<ProductionCompany> findActive() {
        List<ProductionCompany> result = new ArrayList<>();
        for (ProductionCompany c : companiesById.values()) {
            if (c.getStatus() == CompanyStatus.ACTIVE)
                result.add(c);
        }
        return result;
    }

    @Override
    public List<ProductionCompany> findByFounder(int founderUserId) {
        List<ProductionCompany> result = new ArrayList<>();
        for (ProductionCompany c : companiesById.values()) {
            if (c.getFounderId() == founderUserId)
                result.add(c);
        }
        return result;
    }

    @Override
    public List<ProductionCompany> findAll() {
        return new ArrayList<>(companiesById.values());
    }

    @Override
    public int nextId() {
        return idSequence.getAndIncrement();
    }
}
