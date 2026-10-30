package com.ticketing.system.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * {@code V6__receipt_lines_refund_state_not_null.sql} (#175, ADR-001) on H2, the database dev and the
 * JPA suites run on.
 *
 * <p>A line written before refund state moved onto the lines has no state, which read as NONE. V6
 * writes that NONE into the row and makes the column NOT NULL, so no reader has to know the rule. A
 * receipt flagged refunded with every line NONE still counts as fully refunded
 * ({@code OrderReceipt}'s legacy rule), so the backfill changes no receipt's meaning.
 */
class ReceiptLineRefundStateMigrationTest {

    private static final LocalDateTime PURCHASED = LocalDateTime.of(2026, 1, 1, 12, 0);
    private static final int RECEIPT_ID = 1;
    private static final int LEGACY_TICKET_ID = 10;
    private static final int NEW_TICKET_ID = 11;
    private static final String INSERT_RECEIPT = "insert into receipts"
            + " (receipt_id, is_refunded, total_price, user_id, purchase_time) values (?, false, 20, 1, ?)";
    private static final String INSERT_LINE = "insert into receipt_lines"
            + " (receipt_id, event_id, price, ticket_id, zone_id, added_at, refund_state) values (?, 1, 10, ?, 1, ?, ?)";

    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;

    @BeforeEach
    void freshDatabase() {
        dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:v6-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
    }

    @Test
    void GivenALineWithNoRefundStateBeforeV6_WhenMigrated_ThenItsStateIsNone() {
        migrateTo("5");
        jdbc.update(INSERT_RECEIPT, RECEIPT_ID, PURCHASED);
        jdbc.update(INSERT_LINE, RECEIPT_ID, LEGACY_TICKET_ID, PURCHASED, null);

        migrateTo("latest");

        assertThat(jdbc.queryForObject("select refund_state from receipt_lines where ticket_id = ?", String.class,
                        LEGACY_TICKET_ID))
                .as("a line with no state read as NONE, and V6 writes that into the row")
                .isEqualTo("NONE");
    }

    @Test
    void GivenV6Applied_WhenALineWithNoRefundStateIsInserted_ThenTheColumnRefusesIt() {
        migrateTo("latest");
        jdbc.update(INSERT_RECEIPT, RECEIPT_ID, PURCHASED);

        assertThatThrownBy(() -> jdbc.update(INSERT_LINE, RECEIPT_ID, NEW_TICKET_ID, PURCHASED, null))
                .as("refund_state is NOT NULL")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // Control for the refusal above: a line with a state inserts, so the refusal comes from the
    // constraint and not from how the test writes its rows.
    @Test
    void GivenV6Applied_WhenALineWithARefundStateIsInserted_ThenItIsStored() {
        migrateTo("latest");
        jdbc.update(INSERT_RECEIPT, RECEIPT_ID, PURCHASED);

        assertThatCode(() -> jdbc.update(INSERT_LINE, RECEIPT_ID, NEW_TICKET_ID, PURCHASED, "NONE"))
                .doesNotThrowAnyException();
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
