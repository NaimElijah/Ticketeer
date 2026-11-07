package com.ticketing.system.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * {@code V10__active_orders_guest_session_unique.sql} (#224, business rule §1 #8) on H2, the database dev
 * and the JPA suites run on: one cart per guest session. A member cart keeps the session id of the session
 * it is attached to, so the key is on {@code guest_session_id}, which only a guest cart fills, and a guest
 * cart and a member cart may still share a session.
 */
class ActiveOrderGuestSessionUniqueMigrationTest {

    private static final String BEFORE_V10 = "9";
    private static final String V10 = "10";
    private static final String GUEST_SESSION_KEY = "uk_active_orders_guest_session_id";
    private static final LocalDateTime CREATED = LocalDateTime.of(2026, 1, 1, 12, 0);
    private static final int MEMBER_ID = 1;
    private static final String SESSION_ID = "sid-shared";
    private static final String INSERT_CART = "insert into active_orders"
            + " (order_key, user_id, session_id, status, created_at) values (?, ?, ?, 'PRE_CHECKOUT', ?)";

    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;

    @BeforeEach
    void freshDatabase() {
        dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:v10-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
    }

    // DB_CLOSE_DELAY=-1 would otherwise keep each test's database alive for the rest of the fork.
    @AfterEach
    void dropDatabase() {
        jdbc.execute("SHUTDOWN");
    }

    // Two carts of one guest session, written by the race V10 closes, fail the migration, and Flyway stops
    // the boot. H2 names the index behind the constraint (PUBLIC.UK_..._INDEX_n), so the name is matched
    // by containment, ignoring case, as ConstraintViolations does.
    @Test
    void GivenTwoGuestCartsOfOneSessionBeforeV10_WhenMigratedToV10_ThenTheMigrationFailsOnTheGuestSessionKey() {
        migrateTo(BEFORE_V10);
        jdbc.update(INSERT_CART, "guest-first", null, SESSION_ID, CREATED);
        jdbc.update(INSERT_CART, "guest-second", null, SESSION_ID, CREATED);

        assertThatThrownBy(() -> migrateTo(V10))
                .as("V10 cannot add %s over two guest carts of one session", GUEST_SESSION_KEY)
                .isInstanceOf(FlywayException.class)
                .hasRootCauseInstanceOf(SQLException.class)
                .satisfies(failure -> assertThat(rootCause(failure).getMessage().toLowerCase(Locale.ROOT))
                        .as("the database error names the key it could not add")
                        .contains(GUEST_SESSION_KEY));
    }

    // The backfill copies session_id only where user_id is null: a member cart attached to the session
    // keeps guest_session_id null, so it never collides with the guest cart of that session.
    @Test
    void GivenAGuestCartAndAMemberCartOnOneSessionBeforeV10_WhenMigratedToV10_ThenOnlyTheGuestCartGetsTheGuestSessionId() {
        migrateTo(BEFORE_V10);
        jdbc.update(INSERT_CART, "guest", null, SESSION_ID, CREATED);
        jdbc.update(INSERT_CART, "member", MEMBER_ID, SESSION_ID, CREATED);

        migrateTo(V10);

        assertThat(guestSessionIdOf("guest")).as("the guest cart's guest_session_id").isEqualTo(SESSION_ID);
        assertThat(guestSessionIdOf("member")).as("the member cart's guest_session_id").isNull();
    }

    private String guestSessionIdOf(String orderKey) {
        return jdbc.queryForObject("select guest_session_id from active_orders where order_key = ?",
                String.class, orderKey);
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
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
