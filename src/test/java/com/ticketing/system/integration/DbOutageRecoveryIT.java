package com.ticketing.system.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
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
 * 30-second default. A read already in flight when the database hangs is bounded by the PostgreSQL
 * driver's {@code socketTimeout}, which the shipped configuration sets from {@code database.socket-timeout}
 * (#173). This test goes through that same setting, shortened to {@value #SOCKET_TIMEOUT_SECONDS} s.
 */
@Testcontainers
@SpringBootTest(properties = {
        "spring.datasource.hikari.maximum-pool-size=2",
        // No idle floor, so retiring the idle connections does not start a refill before the pause.
        "spring.datasource.hikari.minimum-idle=0",
        "spring.datasource.hikari.connection-timeout=2000",
        "spring.datasource.hikari.validation-timeout=1000",
        "database.socket-timeout=" + DbOutageRecoveryIT.SOCKET_TIMEOUT_SECONDS + "s"})
@ActiveProfiles("test")
@DirtiesContext
class DbOutageRecoveryIT {

    static final String SOCKET_TIMEOUT_SECONDS = "3";
    // The read's own timeout plus room for the container pause to take effect and the error to surface.
    private static final Duration IN_FLIGHT_READ_BOUND = Duration.ofSeconds(Long.parseLong(SOCKET_TIMEOUT_SECONDS) + 7);
    private static final String SLOW_READ = "select pg_sleep(2)";
    private static final Duration RECOVERY_DEADLINE = Duration.ofSeconds(30);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(250);

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = PostgresContainers.newContainer();

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

    // A read lent its connection before the pause waits for an answer that never comes; only the
    // driver's socket timeout ends it. Without one it holds its pool connection until the database
    // returns, and enough such reads exhaust the pool.
    @Test
    void GivenAReadInFlight_WhenTheDatabaseHangs_ThenTheReadFailsWithinTheSocketTimeout() throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        ExecutorService reader = Executors.newSingleThreadExecutor();
        try {
            Future<Object> read = reader.submit(() -> jdbc.queryForObject(SLOW_READ, Object.class));
            await().atMost(RECOVERY_DEADLINE).pollInterval(POLL_INTERVAL).until(() -> slowReadIsRunning(jdbc));
            POSTGRES.getDockerClient().pauseContainerCmd(POSTGRES.getContainerId()).exec();
            try {
                Throwable outcome = outcomeWithin(read, IN_FLIGHT_READ_BOUND);
                assertThat(outcome)
                        .as("the in-flight read ends within %s, with an error", IN_FLIGHT_READ_BOUND)
                        .isNotNull()
                        .isNotInstanceOf(TimeoutException.class);
            } finally {
                resumeDatabase();
            }
        } finally {
            reader.shutdownNow();
        }
    }

    private static boolean slowReadIsRunning(JdbcTemplate jdbc) {
        Integer running = jdbc.queryForObject(
                "select count(*) from pg_stat_activity where state = 'active' and query = ?", Integer.class, SLOW_READ);
        return running != null && running > 0;
    }

    /** What the read threw, or a TimeoutException if it was still running at the bound; null if it returned. */
    private static Throwable outcomeWithin(Future<Object> read, Duration bound) throws InterruptedException {
        try {
            read.get(bound.toMillis(), TimeUnit.MILLISECONDS);
            return null;
        } catch (ExecutionException e) {
            return e.getCause();
        } catch (TimeoutException e) {
            return e;
        }
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
