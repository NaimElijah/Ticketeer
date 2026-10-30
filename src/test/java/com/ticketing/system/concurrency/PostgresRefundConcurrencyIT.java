package com.ticketing.system.concurrency;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.ticketing.system.support.PostgresContainers;

/**
 * Runs {@link RefundConcurrencyTest} on PostgreSQL, the production database, instead of H2 (ADR-006, #171). The two
 * databases implement row locks, lock waits and isolation differently, so passing on H2 alone does not
 * prove the race is closed in production.
 */
@Testcontainers
@DirtiesContext
class PostgresRefundConcurrencyIT extends RefundConcurrencyTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = PostgresContainers.newContainer();
}
