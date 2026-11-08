package com.ticketing.system.integration;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.ticketing.system.support.PostgresContainers;
import com.ticketing.system.support.RunsOnPostgres;

/**
 * Runs {@link ConcurrentRegistrationJpaTest} on PostgreSQL, the production database (ADR-006, #171). There
 * the losing INSERT waits on the winner's uncommitted {@code uk_users_username} or {@code uk_users_email}
 * entry and fails only once the winner commits, so the refusal must still reach the loser as a duplicate
 * username or email.
 */
@Testcontainers
@RunsOnPostgres
@DirtiesContext
class PostgresConcurrentRegistrationIT extends ConcurrentRegistrationJpaTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = PostgresContainers.newContainer();
}
