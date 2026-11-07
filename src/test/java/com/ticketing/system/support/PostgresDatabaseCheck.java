package com.ticketing.system.support;

import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/**
 * The check behind {@link RunsOnPostgres}: fails a test whose Spring context's {@code DataSource} is not
 * PostgreSQL (#189).
 *
 * <p>It runs before each test rather than once per class, because the context, and with it the container's
 * connection details, exists only once {@code SpringExtension} has prepared the test instance. Every
 * {@code BeforeEachCallback} still runs before the class's own {@code @BeforeEach} methods, so no setup
 * writes to the wrong database first.
 */
final class PostgresDatabaseCheck implements BeforeEachCallback {

    /** What the PostgreSQL JDBC driver reports as {@code DatabaseMetaData.getDatabaseProductName()}. */
    static final String POSTGRES_PRODUCT_NAME = "PostgreSQL";

    @Override
    public void beforeEach(ExtensionContext context) throws SQLException {
        requirePostgres(SpringExtension.getApplicationContext(context).getBean(DataSource.class));
    }

    /**
     * @param dataSource the data source the test's context uses
     * @throws AssertionError naming the database it found, if that is not PostgreSQL
     * @throws SQLException if no connection can be opened to ask
     */
    static void requirePostgres(DataSource dataSource) throws SQLException {
        String productName;
        try (Connection connection = dataSource.getConnection()) {
            productName = connection.getMetaData().getDatabaseProductName();
        }
        if (!POSTGRES_PRODUCT_NAME.equals(productName)) {
            throw new AssertionError("this test must run on " + POSTGRES_PRODUCT_NAME + ", but its context's"
                    + " DataSource is " + productName + ": is the container's @ServiceConnection missing?");
        }
    }
}
