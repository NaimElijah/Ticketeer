package com.ticketing.system.unit.infrastructure.persistence.ProductionCompanyPersistence;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.ticketing.system.support.PostgresContainers;

/**
 * Runs {@link JpaProductionCompanyRepositoryContractTest}, and so the whole {@link IProductionCompanyRepositoryContractTest} suite, on PostgreSQL, the production
 * database, instead of H2 (ADR-006). The inherited {@code @DataJpaTest} keeps the container's
 * datasource: {@code @AutoConfigureTestDatabase} replaces only a datasource that is not a test one.
 */
@Testcontainers
@DirtiesContext
class PostgresProductionCompanyRepositoryContractIT extends JpaProductionCompanyRepositoryContractTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = PostgresContainers.newContainer();
}
