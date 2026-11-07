package com.ticketing.system.support;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * {@link PostgresDatabaseCheck} fails a test whose context runs on H2, the database a {@code test} context
 * falls back to (#189). Without this, a check that never fired would let every {@code *IT} pass on H2.
 *
 * <p>The passing case needs a PostgreSQL container, so it is every {@code *IT} under failsafe, not a test
 * here. The JUnit launcher is not on the test classpath, so the check is called directly, as
 * {@code BaseDomainTestHarnessTest} does for its harness.
 */
class PostgresDatabaseCheckTest {

    // No DB_CLOSE_DELAY: the in-memory database closes with the check's only connection.
    private static final String H2_URL = "jdbc:h2:mem:postgres-check-" + UUID.randomUUID();

    @Test
    void GivenADataSourceOnH2_WhenChecked_ThenTheTestFailsNamingTheDatabaseItFound() {
        DriverManagerDataSource onH2 = new DriverManagerDataSource(H2_URL, "sa", "");

        assertThatThrownBy(() -> PostgresDatabaseCheck.requirePostgres(onH2))
                .as("a context on H2 must fail a test that has to run on PostgreSQL")
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("is H2")
                .hasMessageContaining(PostgresDatabaseCheck.POSTGRES_PRODUCT_NAME);
    }
}
