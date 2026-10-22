package com.ticketing.system.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.Comparator;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.ticketing.system.support.PostgresContainers;

/**
 * The schema contract of every deployment (SLR.7, #31, ADR-007), checked on a clean PostgreSQL.
 *
 * <p>The context boots the way the application does: Flyway applies {@code db/migration} first, then
 * Hibernate validates its mapping against the result, so a context that starts at all proves the
 * migrations and the {@code @Entity} mapping agree. The tests then pin what a restart and a foreign
 * database must do.
 */
@DataJpaTest
@Testcontainers
@DirtiesContext
class SchemaMigrationIT {

    private static final String FOREIGN_SCHEMA = "legacy";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = PostgresContainers.newContainer();

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Environment environment;

    @Test
    void GivenCleanPostgres_WhenContextStarts_ThenEveryMigrationIsAppliedAndMappingValidates() {
        MigrationInfo current = flyway.info().current();
        MigrationVersion newest = Arrays.stream(flyway.info().all())
                .map(MigrationInfo::getVersion)
                .max(Comparator.naturalOrder())
                .orElseThrow();

        assertThat(current.getVersion()).isEqualTo(newest);
        assertThat(current.getState()).isEqualTo(MigrationState.SUCCESS);
        assertThat(flyway.info().pending()).isEmpty();
        // Any other mode would build or patch the schema itself and hide a missing migration.
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
    }

    @Test
    void GivenMigratedDatabase_WhenMigratingAgain_ThenNothingIsApplied() {
        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }

    // Outside the test transaction: Flyway reads the schema on its own connection, which would not
    // see uncommitted DDL.
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void GivenSchemaCreatedOutsideFlyway_WhenMigrating_ThenFlywayRefuses() {
        jdbc.execute("create schema " + FOREIGN_SCHEMA);
        jdbc.execute("create table " + FOREIGN_SCHEMA + ".users (user_id integer primary key)");
        Flyway onForeignSchema = Flyway.configure()
                .configuration(flyway.getConfiguration())
                .schemas(FOREIGN_SCHEMA)
                .load();

        assertThatThrownBy(onForeignSchema::migrate)
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("no schema history table");
    }
}
