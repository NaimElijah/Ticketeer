package com.ticketing.system.integration;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.ticketing.system.support.PostgresContainers;
import com.ticketing.system.support.RunsOnPostgres;

/**
 * Runs {@link CheckoutFreezeRaceJpaTest} on PostgreSQL, the production database (ADR-006, #171), with its
 * spies inherited. There the attempt's freeze loses on the order's {@code @Version} as PostgreSQL reports a
 * stale update, and the retry must find the cart and its holds as the other tab left them.
 */
@Testcontainers
@RunsOnPostgres
@DirtiesContext
class PostgresCheckoutFreezeRaceIT extends CheckoutFreezeRaceJpaTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = PostgresContainers.newContainer();
}
