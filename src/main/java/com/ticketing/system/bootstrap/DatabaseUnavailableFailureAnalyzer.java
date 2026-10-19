package com.ticketing.system.bootstrap;

import org.hibernate.HibernateException;
import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.core.env.Environment;

/**
 * Reports an unreachable database at startup as such, instead of Hibernate's dialect error.
 *
 * <p>Every run uses the JPA repositories (ADR-005), so a database that cannot be reached stops the
 * boot. Hibernate catches the connection failure while detecting the dialect, logs it only as the
 * {@code HHH000342} warning, and fails with "Unable to determine Dialect without JDBC metadata
 * (please set … 'hibernate.dialect')". That advice is wrong here: setting a dialect only moves the
 * failure to the first query. This analyzer turns that failure into a report that names the
 * datasource and the settings to check.
 *
 * <p>Registered in {@code META-INF/spring.factories}. Spring Boot passes the {@link Environment} to
 * the constructor.
 */
public class DatabaseUnavailableFailureAnalyzer extends AbstractFailureAnalyzer<HibernateException> {

    // Hibernate's own wording for "no JDBC connection while building the JDBC environment".
    // DatabaseUnavailableFailureAnalyzerTest boots Hibernate against a closed port, so a change of
    // wording in a Hibernate upgrade fails that test.
    private static final String NO_JDBC_METADATA = "Unable to determine Dialect without JDBC metadata";
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
     * @return the analysis when {@code cause} is Hibernate's no-JDBC-metadata failure, else {@code null}
     *         so the next analyzer gets a chance
     */
    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, HibernateException cause) {
        String message = cause.getMessage();
        if (message == null || !message.contains(NO_JDBC_METADATA)) {
            return null;
        }
        String description = "The application could not connect to its database at " + datasourceUrl()
                + ". Every run stores its data in this database, so startup stops here. The"
                + " connection error is logged above (HHH000342).";
        String action = "Start the database or make it reachable, and check DB_HOST, DB_PORT, DB_NAME,"
                + " DB_USER and DB_PASSWORD (or spring.datasource.*). For a local run without a database"
                + " server, use the dev profile, which runs on in-memory H2.";
        return new FailureAnalysis(description, action, rootFailure);
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
