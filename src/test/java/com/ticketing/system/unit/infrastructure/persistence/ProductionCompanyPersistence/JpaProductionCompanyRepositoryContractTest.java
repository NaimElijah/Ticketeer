package com.ticketing.system.unit.infrastructure.persistence.ProductionCompanyPersistence;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.ticketing.system.organization.application.port.out.ProductionCompanyRepository;
import com.ticketing.system.organization.adapter.out.persistence.JpaProductionCompanyRepository;
import com.ticketing.system.organization.adapter.out.persistence.SpringDataProductionCompanyRepository;

/**
 * Runs the {@link IProductionCompanyRepositoryContractTest} suite against the JPA adapter on an
 * embedded H2 schema. {@code @ActiveProfiles("jpa")} activates {@link JpaProductionCompanyRepository};
 * {@code @DataJpaTest} provides H2 + real {@code companies}, {@code company_owner_ids} and
 * {@code company_managers} tables (plus the JSON {@code purchase_policy} column). Each test starts
 * from an empty schema ({@link #cleanTable()}) so the suite is order-independent. CI never touches a
 * real database.
 */
@DataJpaTest
@ActiveProfiles("jpa")
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
}
