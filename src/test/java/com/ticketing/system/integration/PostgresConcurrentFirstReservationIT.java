package com.ticketing.system.integration;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.ticketing.system.support.PostgresContainers;
import com.ticketing.system.support.RunsOnPostgres;

/**
 * Runs {@link ConcurrentFirstReservationJpaTest} on PostgreSQL, the production database (ADR-006, #171).
 * There the losing cart's INSERT waits on the winner's uncommitted {@code uk_active_orders_user_id} entry,
 * or for a guest session on its {@code uk_active_orders_guest_session_id} entry (#224), and fails only once
 * the winner commits, so the translation to a retryable concurrent change must hold for the error
 * PostgreSQL raises at that point.
 */
@Testcontainers
@RunsOnPostgres
@DirtiesContext
class PostgresConcurrentFirstReservationIT extends ConcurrentFirstReservationJpaTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = PostgresContainers.newContainer();

    @Autowired
    private JdbcTemplate jdbc;

    // A cart INSERT that does not wait finishes, and its save then releases the held one; one that waits on the
    // held cart's key entry shows as a lock wait. The probe runs inside the held save's transaction, and there
    // PostgreSQL reads pg_stat_activity's sessions and their query text once, at the first access, and keeps
    // that copy until the transaction ends; only wait_event_type is read live. Clearing the copy before each
    // probe lets the query filter see the INSERT the other session started after the first probe.
    @Override
    protected boolean aSessionIsInsideACartInsert() {
        jdbc.execute("select pg_stat_clear_snapshot()");
        return count("select count(*) from pg_stat_activity where datname = current_database()"
                + " and wait_event_type = 'Lock' and query ilike 'insert into active_orders%'") > 0;
    }
}
