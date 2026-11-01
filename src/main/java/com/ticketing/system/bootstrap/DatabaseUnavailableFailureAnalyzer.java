package com.ticketing.system.bootstrap;

import java.sql.SQLException;
import java.util.Set;

import org.hibernate.HibernateException;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.diagnostics.FailureAnalyzer;
import org.springframework.core.env.Environment;

/**
 * Reports a database the application cannot connect or sign in to at startup as such, instead of a
 * raw stack trace or Hibernate's dialect error.
 *
 * <p>Every run uses the JPA repositories (ADR-005), so a database that cannot be reached stops the
 * boot. The first component to connect raises the failure:
 * <ul>
 *   <li><b>Flyway</b>, which migrates before Hibernate starts (ADR-007), fails with the driver's
 *       {@link SQLException} in its cause chain. Its SQLState class says what went wrong: {@code 08}
 *       connection exception (refused or lost), {@code 28} invalid authorization (wrong user or
 *       password, login rejected), or {@code 3D} invalid catalog name (no such database).</li>
 *   <li><b>Hibernate</b>, when it connects first, catches any of these while detecting the dialect,
 *       logs it only as the {@code HHH000342} warning, and fails with "Unable to determine Dialect
 *       without JDBC metadata (please set … 'hibernate.dialect')". That advice is wrong here:
 *       setting a dialect only moves the failure to the first query.</li>
 * </ul>
 * This analyzer turns either failure into a report that names the datasource and the settings to
 * check. Any other failure, including a SQL error of another class, is left to the next analyzer.
 *
 * <p>Registered in {@code META-INF/spring.factories}. Spring Boot passes the {@link Environment} to
 * the constructor.
 */
public class DatabaseUnavailableFailureAnalyzer implements FailureAnalyzer {

    // Hibernate's own wording for "no JDBC connection while building the JDBC environment".
    // DatabaseUnavailableFailureAnalyzerTest boots Hibernate against a closed port, so a change of
    // wording in a Hibernate upgrade fails that test.
    private static final String NO_JDBC_METADATA = "Unable to determine Dialect without JDBC metadata";
    // SQL-standard SQLState classes (the first two characters) that DB_HOST, DB_PORT, DB_NAME, DB_USER
    // or DB_PASSWORD can cause: 08 connection exception, 28 invalid authorization specification,
    // 3D invalid catalog name.
    private static final Set<String> MISCONFIGURED_CONNECTION_CLASSES = Set.of("08", "28", "3D");
    private static final int SQL_STATE_CLASS_LENGTH = 2;
    private static final String URL_PROPERTY = "spring.datasource.url";
    private static final String UNKNOWN_URL = "(spring.datasource.url is not set)";

    private final Environment environment;

    /**
     * @param environment the failed application's environment, read for the datasource URL
     */
    public DatabaseUnavailableFailureAnalyzer(Environment environment) {
        this.environment = environment;
    }

    /**
     * @param failure the startup failure, searched through its whole cause chain
     * @return the analysis when the chain holds a failure to connect or sign in to the database,
     *         else {@code null} so the next analyzer gets a chance
     */
    @Override
    public FailureAnalysis analyze(Throwable failure) {
        if (!isConnectionFailure(failure)) {
            return null;
        }
        String description = "The application could not connect or sign in to its database at "
                + datasourceUrl() + ". Every run stores its data in this database, so startup stops"
                + " here. The database's error is logged above.";
        String action = "Start the database or make it reachable, and check DB_HOST, DB_PORT, DB_NAME,"
                + " DB_USER and DB_PASSWORD (or spring.datasource.*). For a local run without a database"
                + " server, use the dev profile, which runs on in-memory H2.";
        return new FailureAnalysis(description, action, failure);
    }

    private static boolean isConnectionFailure(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof HibernateException && cause.getMessage() != null
                    && cause.getMessage().contains(NO_JDBC_METADATA)) {
                return true;
            }
            if (cause instanceof SQLException sql && sql.getSQLState() != null
                    && sql.getSQLState().length() >= SQL_STATE_CLASS_LENGTH
                    && MISCONFIGURED_CONNECTION_CLASSES.contains(
                            sql.getSQLState().substring(0, SQL_STATE_CLASS_LENGTH))) {
                return true;
            }
        }
        return false;
    }

    /** The configured URL without its query string, which can carry credentials. */
    private String datasourceUrl() {
        String url = environment.getProperty(URL_PROPERTY);
        if (url == null || url.isBlank()) {
            return UNKNOWN_URL;
        }
        return DatasourceUrls.withoutQuery(url);
    }
}
