package com.ticketing.system.bootstrap;

import java.sql.SQLException;

import org.hibernate.HibernateException;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.diagnostics.FailureAnalyzer;
import org.springframework.core.env.Environment;

/**
 * Reports an unreachable database at startup as such, instead of a raw stack trace or Hibernate's
 * dialect error.
 *
 * <p>Every run uses the JPA repositories (ADR-005), so a database that cannot be reached stops the
 * boot. The first component to connect raises the failure:
 * <ul>
 *   <li><b>Flyway</b>, which migrates before Hibernate starts (ADR-007), fails with a
 *       {@link SQLException} in its cause chain whose SQLState is in class {@code 08}, connection
 *       exception, as every JDBC driver reports a refused or lost connection.</li>
 *   <li><b>Hibernate</b>, when it connects first, catches the connection failure while detecting the
 *       dialect, logs it only as the {@code HHH000342} warning, and fails with "Unable to determine
 *       Dialect without JDBC metadata (please set … 'hibernate.dialect')". That advice is wrong here:
 *       setting a dialect only moves the failure to the first query.</li>
 * </ul>
 * This analyzer turns either failure into a report that names the datasource and the settings to
 * check. Any other failure, including a SQL error that is not a connection failure, is left to the
 * next analyzer.
 *
 * <p>Registered in {@code META-INF/spring.factories}. Spring Boot passes the {@link Environment} to
 * the constructor.
 */
public class DatabaseUnavailableFailureAnalyzer implements FailureAnalyzer {

    // Hibernate's own wording for "no JDBC connection while building the JDBC environment".
    // DatabaseUnavailableFailureAnalyzerTest boots Hibernate against a closed port, so a change of
    // wording in a Hibernate upgrade fails that test.
    private static final String NO_JDBC_METADATA = "Unable to determine Dialect without JDBC metadata";
    // SQL standard SQLState class 08: connection exception (08001 cannot connect, 08006 connection
    // failure, ...).
    private static final String CONNECTION_EXCEPTION_CLASS = "08";
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
     * @return the analysis when the chain holds a database connection failure, else {@code null} so
     *         the next analyzer gets a chance
     */
    @Override
    public FailureAnalysis analyze(Throwable failure) {
        if (!isConnectionFailure(failure)) {
            return null;
        }
        String description = "The application could not connect to its database at " + datasourceUrl()
                + ". Every run stores its data in this database, so startup stops here. The"
                + " connection error is logged above.";
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
                    && sql.getSQLState().startsWith(CONNECTION_EXCEPTION_CLASS)) {
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
        int query = url.indexOf('?');
        return query < 0 ? url : url.substring(0, query);
    }
}
