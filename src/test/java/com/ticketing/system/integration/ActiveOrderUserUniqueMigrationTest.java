package com.ticketing.system.integration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * {@code V7__active_orders_user_id_unique.sql} (#186, business rule §1 #8) on H2, the database dev and
 * the JPA suites run on: one cart per member, while guest carts, which have no user, stay unconstrained.
 */
class ActiveOrderUserUniqueMigrationTest {

    private static final LocalDateTime CREATED = LocalDateTime.of(2026, 1, 1, 12, 0);
    private static final int MEMBER_ID = 1;
    private static final String INSERT_CART = "insert into active_orders"
            + " (order_key, user_id, session_id, status, created_at) values (?, ?, ?, 'PRE_CHECKOUT', ?)";

    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;

    @BeforeEach
    void freshDatabase() {
        dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:v7-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
    }

    @Test
    void GivenV7Applied_WhenASecondCartOfOneMemberIsInserted_ThenTheConstraintRefusesIt() {
        migrateTo("latest");
        jdbc.update(INSERT_CART, "first", MEMBER_ID, null, CREATED);

        assertThatThrownBy(() -> jdbc.update(INSERT_CART, "second", MEMBER_ID, null, CREATED))
                .as("uk_active_orders_user_id: one cart per member")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // NULLs are distinct in a unique constraint, so guest carts, which have no user, are not limited.
    @Test
    void GivenV7Applied_WhenTwoGuestCartsAreInserted_ThenBothAreStored() {
        migrateTo("latest");

        assertThatCode(() -> {
            jdbc.update(INSERT_CART, "guest-a", null, "sid-a", CREATED);
            jdbc.update(INSERT_CART, "guest-b", null, "sid-b", CREATED);
        }).doesNotThrowAnyException();
    }

    // Two carts of one member, written by the race V7 closes, fail the migration, and Flyway stops the boot.
    @Test
    void GivenTwoCartsOfOneMemberBeforeV7_WhenMigrated_ThenTheMigrationFails() {
        migrateTo("6");
        jdbc.update(INSERT_CART, "first", MEMBER_ID, null, CREATED);
        jdbc.update(INSERT_CART, "second", MEMBER_ID, null, CREATED);

        assertThatThrownBy(() -> migrateTo("latest"))
                .as("V7 cannot add uk_active_orders_user_id over a duplicate")
                .isInstanceOf(FlywayException.class);
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
