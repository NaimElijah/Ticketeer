package com.ticketing.system.integration;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.ticketing.system.support.PostgresContainers;

/**
 * Runs {@link ConcurrentFirstReservationJpaTest} on PostgreSQL, the production database (ADR-006, #171).
 * There the losing cart's INSERT waits on the winner's uncommitted {@code uk_active_orders_user_id} entry,
 * or for a guest session on its {@code uk_active_orders_guest_session_id} entry (#224), and fails only once
 * the winner commits, so the translation to a retryable concurrent change must hold for the error
 * PostgreSQL raises at that point.
 */
@Testcontainers
@DirtiesContext
class PostgresConcurrentFirstReservationIT extends ConcurrentFirstReservationJpaTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = PostgresContainers.newContainer();

    // A cart INSERT that does not wait finishes, and its save then releases the held one; one that waits on the
    // held cart's key entry shows as a lock wait.
    @Override
    protected boolean aSessionIsInsideACartInsert() {
        return count("select count(*) from pg_stat_activity where wait_event_type = 'Lock'") > 0;
    }
}
