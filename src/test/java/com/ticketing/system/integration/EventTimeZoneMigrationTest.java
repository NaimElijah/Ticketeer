package com.ticketing.system.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * {@code V2__event_time_zone.sql} (#74, ADR-008) on H2, the database dev and the JPA suites run on.
 * {@code SchemaMigrationIT} applies the same file to PostgreSQL.
 */
class EventTimeZoneMigrationTest {

    private static final String LEGACY_ZONE = "Asia/Jerusalem";
    private static final String INSERT_V1_EVENT = "insert into events (company_id, id, category, name,"
            + " purchase_policy, status) values (1, ?, 'CONCERT', 'Legacy', '{}', 'DRAFT')";

    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;

    @BeforeEach
    void freshDatabase() {
        dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:v2-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
    }

    @Test
    void GivenEventFromBeforeV2_WhenMigrated_ThenItsTimeZoneIsAsiaJerusalem() {
        migrateTo("1");
        jdbc.update(INSERT_V1_EVENT, 1);

        migrateTo("latest");

        assertThat(jdbc.queryForObject("select time_zone from events where id = 1", String.class))
                .isEqualTo(LEGACY_ZONE);
    }

    @Test
    void GivenV2Applied_WhenAnOlderInstanceInsertsWithoutTheColumn_ThenTheDefaultFillsIt() {
        migrateTo("latest");

        jdbc.update(INSERT_V1_EVENT, 2);

        assertThat(jdbc.queryForObject("select time_zone from events where id = 2", String.class))
                .isEqualTo(LEGACY_ZONE);
    }

    private void migrateTo(String target) {
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .target(target)
                .load()
                .migrate();
    }
}
