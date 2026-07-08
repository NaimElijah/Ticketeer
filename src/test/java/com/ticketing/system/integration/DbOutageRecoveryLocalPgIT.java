package com.ticketing.system.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import com.ticketing.system.ui.support.ServiceErrors;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Manual robustness check for #427, run against a real local Postgres through the same HikariCP pool
 * production uses. It drives the full TA loop at the connection layer — DB up → stop it → DB up again
 * — and asserts the three properties the issue needs:
 * <ol>
 *   <li><b>No crash, recognized outage:</b> a query while Postgres is stopped throws, and
 *       {@link ServiceErrors#isDatabaseUnavailable} recognizes the <i>real</i> exception (so the UI
 *       would show the friendly "temporarily unavailable" message, not a stack trace).</li>
 *   <li><b>Recovery:</b> after Postgres restarts, the <i>same</i> pool serves queries again — no app
 *       restart (HikariCP validate-on-borrow from #362).</li>
 * </ol>
 *
 * <p><b>Disabled by default</b> (needs a local Postgres + {@code pg_ctl}); CI never runs it. To run:
 * <pre>
 *   pg_ctl -D /opt/homebrew/var/postgresql@14 -l /tmp/pg_eval.log -w start
 *   createdb ticketing_eval
 *   LOCAL_PG_IT=true ./mvnw -o -Pno-vaadin test -Dtest=DbOutageRecoveryLocalPgIT
 * </pre>
 * Overridable via env: {@code PG_CTL}, {@code PG_DATA}, {@code PG_URL}, {@code PG_USER}, {@code PG_PASSWORD}.
 */
@EnabledIfEnvironmentVariable(named = "LOCAL_PG_IT", matches = "true")
class DbOutageRecoveryLocalPgIT {

    private static final String PG_CTL   = env("PG_CTL", "/opt/homebrew/bin/pg_ctl");
    private static final String PG_DATA  = env("PG_DATA", "/opt/homebrew/var/postgresql@14");
    private static final String PG_LOG   = "/tmp/pg_eval.log";
    private static final String PG_URL   = env("PG_URL", "jdbc:postgresql://localhost:5432/ticketing_eval");
    private static final String PG_USER  = env("PG_USER", System.getProperty("user.name"));
    private static final String PG_PASS  = env("PG_PASSWORD", "");

    @Test
    void databaseDown_isRecognizedAsAnOutage_andRecoversAfterRestart() throws Exception {
        try (HikariDataSource ds = pool()) {
            JdbcTemplate jdbc = new JdbcTemplate(ds);

            // Phase 1 — Postgres up: a query works.
            assertEquals(1, jdbc.queryForObject("SELECT 1", Integer.class));

            // Phase 2 — stop Postgres: the same query fails, and we recognize it as an outage.
            pgCtl("stop");
            DataAccessException outage = assertThrows(DataAccessException.class,
                () -> jdbc.queryForObject("SELECT 1", Integer.class));
            System.out.println("[#427] real Postgres-down surfaced as: " + describe(outage));
            assertTrue(ServiceErrors.isDatabaseUnavailable(outage),
                "the real Postgres-down exception must be classified as a DB outage, was: " + describe(outage));

            // Phase 3 — restart Postgres: the SAME pool serves queries again (no app restart).
            pgCtl("start");
            assertEquals(1, queryWithRetry(jdbc),
                "after Postgres returns, the pool must recover without recreating the datasource");
        }
    }

    /** The literal TA scenario: Postgres is already down when a fresh request (login) tries to connect. */
    @Test
    void freshRequestWhileDown_isRecognizedAsAnOutage() throws Exception {
        pgCtl("stop");
        try (HikariDataSource ds = pool()) {
            JdbcTemplate jdbc = new JdbcTemplate(ds);
            DataAccessException outage = assertThrows(DataAccessException.class,
                () -> jdbc.queryForObject("SELECT 1", Integer.class));
            System.out.println("[#427] fresh connection while down surfaced as: " + describe(outage));
            assertTrue(ServiceErrors.isDatabaseUnavailable(outage),
                "a fresh connection to a down DB must be classified as an outage, was: " + describe(outage));
        } finally {
            pgCtl("start");
        }
    }

    @AfterAll
    static void ensurePostgresIsLeftRunning() throws Exception {
        pgCtl("start"); // no-op if already running; never leave the box with PG down
    }

    private static HikariDataSource pool() {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(PG_URL);
        cfg.setUsername(PG_USER);
        cfg.setPassword(PG_PASS);
        cfg.setMaximumPoolSize(2);
        cfg.setMinimumIdle(0);
        cfg.setConnectionTimeout(3000);          // fail fast while the DB is down instead of hanging
        cfg.setInitializationFailTimeout(-1);    // start the pool even if the DB is down (mirrors #362) so
                                                 // the failure surfaces at query time, where the UI handles it
        return new HikariDataSource(cfg);
    }

    // Recovery can take a moment after pg_ctl returns; retry briefly so the assertion isn't flaky.
    private static Integer queryWithRetry(JdbcTemplate jdbc) throws InterruptedException {
        RuntimeException last = null;
        for (int attempt = 0; attempt < 10; attempt++) {
            try {
                return jdbc.queryForObject("SELECT 1", Integer.class);
            } catch (RuntimeException e) {
                last = e;
                Thread.sleep(500);
            }
        }
        throw last;
    }

    private static void pgCtl(String action) throws Exception {
        List<String> cmd = new ArrayList<>(List.of(PG_CTL, "-D", PG_DATA, action, "-w"));
        if ("stop".equals(action)) {
            cmd.add("-m");
            cmd.add("fast");
        } else if ("start".equals(action)) {
            cmd.add("-l");
            cmd.add(PG_LOG);
        }
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        p.waitFor();
        System.out.println("[#427] pg_ctl " + action + ": " + out.trim());
    }

    private static String describe(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable c = t; c != null && c != c.getCause(); c = c.getCause()) {
            if (sb.length() > 0) {
                sb.append(" <- ");
            }
            sb.append(c.getClass().getSimpleName()).append("(").append(c.getMessage()).append(")");
        }
        return sb.toString();
    }

    private static String env(String key, String fallback) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? fallback : v;
    }
}
