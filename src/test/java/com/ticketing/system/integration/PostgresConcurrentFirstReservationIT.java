package com.ticketing.system.integration;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.ticketing.system.support.PostgresContainers;

/**
 * Runs {@link ConcurrentFirstReservationJpaTest} on PostgreSQL, the production database (ADR-006, #171).
 * There the losing cart's INSERT waits on the winner's uncommitted {@code uk_active_orders_user_id} entry
 * and fails only once the winner commits, so the translation to a retryable concurrent change must hold
 * for the error PostgreSQL raises at that point.
 */
@Testcontainers
@DirtiesContext
class PostgresConcurrentFirstReservationIT extends ConcurrentFirstReservationJpaTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = PostgresContainers.newContainer();
}
