package com.ticketing.system.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.sql.SQLException;
import java.time.Duration;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.ticketing.system.catalog.application.service.CatalogService;
import com.ticketing.system.support.PostgresContainers;
import com.ticketing.system.ui.support.ServiceErrors;
import com.zaxxer.hikari.HikariDataSource;

/**
 * The platform survives a database outage and recovers without a restart (SLR.6, V3 4.a; ADR-006).
 *
 * <p>The application boots under {@code test}, on the Jpa adapters and the stub external services,
 * against a real PostgreSQL. Pausing the container freezes the database but keeps its port open,
 * which is how a network partition or a hung server looks to the pool. While it is paused, a
 * catalog read must fail with an error the UI shows as "database unavailable"
 * ({@link ServiceErrors#isDatabaseUnavailable}). Once it resumes, the same read on the same pool must
 * succeed.
 *
 * <p>The pool settings are shortened so the outage shows in seconds rather than after Hikari's
 * 30-second default. {@code socketTimeout} bounds a read on a connection that was lent just before
 * the pause; without it that read would block until the pause ended. Production sets no socket
 * timeout yet (#173), so this test proves recovery once the database returns, not that in-flight
 * reads are bounded.
 */
@Testcontainers
@SpringBootTest(properties = {
        "spring.datasource.hikari.maximum-pool-size=2",
        // No idle floor, so retiring the idle connections does not start a refill before the pause.
        "spring.datasource.hikari.minimum-idle=0",
        "spring.datasource.hikari.connection-timeout=2000",
        "spring.datasource.hikari.validation-timeout=1000"})
@ActiveProfiles("test")
@DirtiesContext
class DbOutageRecoveryIT {

    private static final String SOCKET_TIMEOUT_SECONDS = "3";
    private static final Duration RECOVERY_DEADLINE = Duration.ofSeconds(30);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(250);

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            PostgresContainers.newContainer().withUrlParam("socketTimeout", SOCKET_TIMEOUT_SECONDS);

    @Autowired
    private CatalogService catalog;

    @Autowired
    private DataSource dataSource;

    @Test
    void GivenDatabaseOutage_WhenDatabaseReturns_ThenReadsRecoverWithoutRestart() throws SQLException {
        assertThat(catalog.browseEventCatalog()).isEmpty();

        pauseDatabase();
        try {
            assertThatThrownBy(catalog::browseEventCatalog)
                    .satisfies(outage -> assertThat(ServiceErrors.isDatabaseUnavailable(outage))
                            .as("recognised as a database outage: %s", outage)
                            .isTrue());
        } finally {
            resumeDatabase();
        }

        await().atMost(RECOVERY_DEADLINE)
                .pollInterval(POLL_INTERVAL)
                .ignoreExceptions()
                .untilAsserted(() -> assertThat(catalog.browseEventCatalog()).isEmpty());
    }

    /**
     * Retires the idle connections first. With {@code minimum-idle=0} the pool does not refill, so
     * the read during the outage always needs a new connection and fails on the pool's connection
     * timeout, the same way on every run.
     */
    private void pauseDatabase() throws SQLException {
        dataSource.unwrap(HikariDataSource.class).getHikariPoolMXBean().softEvictConnections();
        POSTGRES.getDockerClient().pauseContainerCmd(POSTGRES.getContainerId()).exec();
    }

    private void resumeDatabase() {
        POSTGRES.getDockerClient().unpauseContainerCmd(POSTGRES.getContainerId()).exec();
    }
}
