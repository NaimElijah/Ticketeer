package com.ticketing.system.concurrency;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.ticketing.system.support.PostgresContainers;
import com.ticketing.system.support.RunsOnPostgres;

/**
 * Runs {@link RefundConcurrencyTest} on PostgreSQL, the production database, instead of H2 (ADR-006, #171). The two
 * databases implement row locks, lock waits and isolation differently, so passing on H2 alone does not
 * prove the race is closed in production. There a refund's {@code FOR UPDATE} waits with no lock timeout,
 * where H2 ends the wait after its own.
 */
@Testcontainers
@RunsOnPostgres
@DirtiesContext
class PostgresRefundConcurrencyIT extends RefundConcurrencyTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = PostgresContainers.newContainer();

    @Override
    protected boolean aSessionWaitsOnALock() {
        return count("select count(*) from pg_stat_activity where wait_event_type = 'Lock'") > 0;
    }
}
