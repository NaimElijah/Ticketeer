package com.ticketing.system.unit.infrastructure.persistence.ProductionCompanyPersistence;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import com.ticketing.system.organization.application.port.out.ProductionCompanyRepository;
import com.ticketing.system.organization.adapter.out.persistence.JpaProductionCompanyRepository;
import com.ticketing.system.organization.adapter.out.persistence.SpringDataProductionCompanyRepository;
import com.ticketing.system.organization.domain.CompanyStatus;
import com.ticketing.system.organization.domain.ProductionCompany;

/**
 * Runs the {@link IProductionCompanyRepositoryContractTest} suite against the JPA adapter on an
 * embedded H2 schema. {@code @Import} adds {@link JpaProductionCompanyRepository};
 * {@code @DataJpaTest} provides H2 + real {@code companies}, {@code company_owner_ids} and
 * {@code company_managers} tables (plus the JSON {@code purchase_policy} column). Each test starts
 * from an empty schema ({@link #cleanTable()}) so the suite is order-independent. CI never touches a
 * real database.
 */
@DataJpaTest
@Import(JpaProductionCompanyRepository.class)
class JpaProductionCompanyRepositoryContractTest extends IProductionCompanyRepositoryContractTest {

    @Autowired
    private JpaProductionCompanyRepository repository;

    @Autowired
    private SpringDataProductionCompanyRepository data;

    @BeforeEach
    void cleanTable() {
        data.deleteAll();
    }

    @Override
    protected ProductionCompanyRepository newRepository() {
        return repository;
    }

    // A new adapter instance models a restarted process (#19, as JpaTicketRepositoryContractTest does for
    // tickets): the id sequence must resume above the rows a previous boot persisted, not start at 1.
    @Test
    void GivenCompaniesPersistedByAPreviousBoot_WhenAFreshAdapterMintsAnId_ThenItIsAboveTheExistingMax() {
        data.saveAllAndFlush(List.of(company(1), company(2), company(3)));

        int id = new JpaProductionCompanyRepository(data).nextId();

        assertTrue(id > 3, "company id after a restart: " + id);
    }

    private static ProductionCompany company(int id) {
        return new ProductionCompany(id, 7, "Company " + id, CompanyStatus.ACTIVE, "a description", 4.5);
    }
}
