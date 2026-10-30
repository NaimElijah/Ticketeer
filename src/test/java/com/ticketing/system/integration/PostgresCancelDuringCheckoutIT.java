package com.ticketing.system.integration;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.ticketing.system.support.PostgresContainers;

/**
 * Runs {@link CancelDuringCheckoutJpaTest} on PostgreSQL, the production database (ADR-006, #171). There
 * the sale's lock is {@code FOR SHARE}, and a cancel's {@code UPDATE} waits for it with no timeout,
 * where H2 takes an exclusive lock and times waits out.
 */
@Testcontainers
@DirtiesContext
class PostgresCancelDuringCheckoutIT extends CancelDuringCheckoutJpaTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = PostgresContainers.newContainer();

    @Override
    protected boolean aSessionWaitsOnALock() {
        return count("select count(*) from pg_stat_activity where wait_event_type = 'Lock'") > 0;
    }
}
