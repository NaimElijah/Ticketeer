package com.ticketing.system.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

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

    // PostgreSQL does not index a foreign key's column on its own, and H2 does, so only PostgreSQL shows a
    // missing one. Without it, every load of a parent's collection scans the whole child table.
    @Test
    void GivenMigratedPostgres_WhenListingForeignKeys_ThenEachColumnLeadsAnIndex() {
        Integer foreignKeys = jdbc.queryForObject("""
                select count(*) from pg_constraint
                where contype = 'f'
                  and connamespace = (select oid from pg_namespace where nspname = current_schema())
                """, Integer.class);
        assertThat(foreignKeys).as("foreign keys in the migrated schema; with none, the check below proves nothing")
                .isPositive();

        List<String> unindexed = jdbc.queryForList("""
                select c.conrelid::regclass::text || '.' || a.attname
                from pg_constraint c
                join pg_attribute a on a.attrelid = c.conrelid and a.attnum = c.conkey[1]
                where c.contype = 'f'
                  and c.connamespace = (select oid from pg_namespace where nspname = current_schema())
                  and not exists (select 1 from pg_index i
                                  where i.indrelid = c.conrelid and i.indkey[0] = c.conkey[1])
                order by 1
                """, String.class);

        assertThat(unindexed).as("foreign-key columns that lead no index").isEmpty();
    }

    // The selective columns the Spring Data repositories filter on that no primary key, unique constraint
    // or foreign-key index already covers. The list is kept by hand, so a newly filtered column must be
    // added here. Left out on purpose, with no index: conversation type and status and company status,
    // which have a handful of values (V5), and companies.founder_id (findByFounderId) and sessions.user_id
    // (existsByUserIdAndKindAndExpiresAtAfter, behind SessionManager.isOnline), which no production code
    // queries yet. The first caller of either adds its index and its entry here.
    @Test
    void GivenMigratedPostgres_WhenListingQueriedColumns_ThenEachLeadsAnIndex() {
        List<String> queried = List.of(
                "events.company_id", "events.status",
                "sessions.expires_at",
                "companies.name",
                "company_appointments.company_id", "company_appointments.target_id",
                "conversations.initiator_id", "conversations.counterparty_id",
                "notifications.recipient_user_id",
                "active_orders.session_id",
                "active_order_items.added_at",
                "receipts.user_id",
                "receipt_lines.event_id",
                "tickets.event_id", "tickets.order_receipt_id");

        List<String> unindexed = queried.stream().filter(column -> !leadsAnIndex(column)).toList();

        assertThat(unindexed).as("queried columns that lead no index").isEmpty();
    }

    private boolean leadsAnIndex(String tableDotColumn) {
        String[] parts = tableDotColumn.split("\\.");
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists (select 1 from pg_index i
                               join pg_attribute a on a.attrelid = i.indrelid and a.attnum = i.indkey[0]
                               where i.indrelid = to_regclass(?) and a.attname = ?)
                """, Boolean.class, parts[0], parts[1]));
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
