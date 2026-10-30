package com.ticketing.system.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * {@code V3__session_kind.sql} and {@code V4__users_username_unique.sql} (#185, ADR-012) on H2, the
 * database dev and the JPA suites run on.
 *
 * <p>Rows written before V3 do not record their kind, and member and admin ids share one id space, so
 * a row with a user cannot be told to be a member's or an admin's. V3 deletes every such row, so
 * members and admins sign in again, and keeps each guest row as a GUEST row.
 */
class SessionKindMigrationTest {

    private static final OffsetDateTime CREATED = OffsetDateTime.of(2026, 1, 1, 12, 0, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime EXPIRES = CREATED.plusHours(1);
    private static final String GUEST_SID = "guest-sid-before-v3";
    private static final String SIGNED_IN_SID = "signed-in-sid-before-v3";
    private static final int USER_ID = 1;
    private static final String INSERT_PRE_V3_SESSION = "insert into sessions"
            + " (session_id, user_id, created_at, expires_at, last_seen_at) values (?, ?, ?, ?, ?)";
    private static final String INSERT_SESSION = "insert into sessions"
            + " (session_id, user_id, kind, created_at, expires_at, last_seen_at) values (?, ?, ?, ?, ?, ?)";
    private static final String INSERT_USER = "insert into users (user_id, age, email, password, username)"
            + " values (?, 30, ?, 'hash', ?)";

    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;

    @BeforeEach
    void freshDatabase() {
        dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:v3-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
    }

    @Test
    void GivenAGuestRowAndASignedInRowFromBeforeV3_WhenMigrated_ThenTheGuestRowIsGuestAndTheOtherIsGone() {
        migrateTo("2");
        jdbc.update(INSERT_PRE_V3_SESSION, GUEST_SID, null, CREATED, EXPIRES, CREATED);
        jdbc.update(INSERT_PRE_V3_SESSION, SIGNED_IN_SID, USER_ID, CREATED, EXPIRES, CREATED);

        migrateTo("latest");

        assertThat(jdbc.queryForObject("select kind from sessions where session_id = ?", String.class, GUEST_SID))
                .as("a guest row stays, as a GUEST row")
                .isEqualTo("GUEST");
        assertThat(jdbc.queryForObject("select count(*) from sessions where session_id = ?", Integer.class,
                        SIGNED_IN_SID))
                .as("a row with a user may be a member's or an admin's, so V3 deletes it")
                .isZero();
    }

    @Test
    void GivenV3Applied_WhenAGuestRowWithAUserIsInserted_ThenTheKindConstraintRefusesIt() {
        migrateTo("latest");

        assertThatThrownBy(() -> jdbc.update(INSERT_SESSION, GUEST_SID, USER_ID, "GUEST", CREATED, EXPIRES, CREATED))
                .as("ck_sessions_kind_user_id: a GUEST row has no user")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void GivenV3Applied_WhenAMemberRowWithoutAUserIsInserted_ThenTheKindConstraintRefusesIt() {
        migrateTo("latest");

        assertThatThrownBy(() -> jdbc.update(INSERT_SESSION, SIGNED_IN_SID, null, "MEMBER", CREATED, EXPIRES, CREATED))
                .as("ck_sessions_kind_user_id: a MEMBER row names its user")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // Control for the two refusals above: rows of the right shape insert, so the refusals come from
    // the constraint and not from how the test writes its rows.
    @Test
    void GivenV3Applied_WhenAGuestRowAndAMemberRowOfTheRightShapeAreInserted_ThenBothAreStored() {
        migrateTo("latest");

        assertThatCode(() -> {
            jdbc.update(INSERT_SESSION, GUEST_SID, null, "GUEST", CREATED, EXPIRES, CREATED);
            jdbc.update(INSERT_SESSION, SIGNED_IN_SID, USER_ID, "MEMBER", CREATED, EXPIRES, CREATED);
        }).doesNotThrowAnyException();
    }

    // V4 adds uk_users_username. A duplicate written before it (by the registration race V4 closes)
    // fails the migration, and Flyway stops the boot.
    @Test
    void GivenTwoUsersWithOneUsernameBeforeV4_WhenMigrated_ThenTheMigrationFails() {
        migrateTo("3");
        jdbc.update(INSERT_USER, 1, "first@example.com", "same.name");
        jdbc.update(INSERT_USER, 2, "second@example.com", "same.name");

        assertThatThrownBy(() -> migrateTo("latest"))
                .as("V4 cannot add uk_users_username over a duplicate")
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
