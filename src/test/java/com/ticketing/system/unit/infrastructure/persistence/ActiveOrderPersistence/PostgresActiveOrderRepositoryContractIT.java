package com.ticketing.system.unit.infrastructure.persistence.ActiveOrderPersistence;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.ticketing.system.support.PostgresContainers;

/**
 * Runs {@link JpaActiveOrderRepositoryContractTest}, and so the whole {@link IActiveOrderRepositoryContractTest} suite, on PostgreSQL, the production
 * database, instead of H2 (ADR-006). The inherited {@code @DataJpaTest} keeps the container's
 * datasource: {@code @AutoConfigureTestDatabase} replaces only a datasource that is not a test one.
 */
@Testcontainers
@DirtiesContext
class PostgresActiveOrderRepositoryContractIT extends JpaActiveOrderRepositoryContractTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = PostgresContainers.newContainer();
}
