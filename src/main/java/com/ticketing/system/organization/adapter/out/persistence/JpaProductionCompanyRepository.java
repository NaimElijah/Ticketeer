package com.ticketing.system.organization.adapter.out.persistence;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.ticketing.system.organization.domain.CompanyStatus;
import com.ticketing.system.organization.application.port.out.ProductionCompanyRepository;
import com.ticketing.system.organization.domain.ProductionCompany;
import com.ticketing.system.shared.exception.CompanyNotFoundException;

/**
 * JPA-backed {@link ProductionCompanyRepository} — active only in the {@code jpa} run/dev profile.
 * Adapts the domain port onto Spring Data ({@link SpringDataProductionCompanyRepository}); the
 * application layer depends only on {@code ProductionCompanyRepository}, never on Spring Data.
 * Owner/manager id lists ({@code @ElementCollection}) and the purchase policy (JSON column) persist
 * by cascade with the company.
 *
 * <p>{@code lockForUpdate}/{@code unlock} are no-ops (concurrency via {@code @Version}).
 * {@code save}/{@code updateCompany} delegate to {@code data.save} under {@code @Version} and are
 * {@code @Transactional} so the adapter is self-sufficient before the service layer gains
 * transactions (#359). {@link #nextId()} keeps the assigned-id design but seeds an in-memory counter
 * from {@code max(companyId)} on first use, so ids survive a restart on a persistent database.
 */
@Repository
@Profile("jpa")
public class JpaProductionCompanyRepository implements ProductionCompanyRepository {

    private final SpringDataProductionCompanyRepository data;
    private final AtomicInteger idSequence = new AtomicInteger(0);
    private volatile boolean seeded = false;

    public JpaProductionCompanyRepository(SpringDataProductionCompanyRepository data) {
        this.data = data;
    }

    @Override
    public void lockForUpdate(Integer id) { /* no-op — @Version optimistic locking */ }

    @Override
    public void unlock(Integer id) { /* no-op */ }

    @Override
    @Transactional
    public void save(ProductionCompany company) {
        data.save(company);
    }

    @Override
    @Transactional
    public void updateCompany(ProductionCompany company) {
        data.save(company);
    }

    @Override
    public ProductionCompany getCompanyById(int companyId) {
        return data.findById(companyId).orElseThrow(() -> new CompanyNotFoundException(companyId));
    }

    @Override
    public Optional<ProductionCompany> findByName(String name) {
        if (name == null) {
            return Optional.empty();
        }
        return data.findByName(name);
    }

    @Override
    public boolean existsByName(String name) {
        return name != null && data.existsByName(name);
    }

    @Override
    public List<ProductionCompany> findActive() {
        return data.findByCompanyStatus(CompanyStatus.ACTIVE);
    }

    @Override
    public List<ProductionCompany> findByFounder(int founderUserId) {
        return data.findByFounderId(founderUserId);
    }

    @Override
    public List<ProductionCompany> findAll() {
        return data.findAll();
    }

    @Override
    public int nextId() {
        ensureSeeded();
        return idSequence.incrementAndGet();
    }

    private void ensureSeeded() {
        if (!seeded) {
            synchronized (this) {
                if (!seeded) {
                    idSequence.set(data.findMaxCompanyId());
                    seeded = true;
                }
            }
        }
    }
}
