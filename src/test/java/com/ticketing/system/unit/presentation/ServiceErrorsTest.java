package com.ticketing.system.unit.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ConnectException;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.util.List;

import com.ticketing.system.shared.exception.BusinessRuleViolationException;
import com.ticketing.system.testutil.LogCapture;
import com.ticketing.system.ui.support.ServiceErrors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.CannotCreateTransactionException;

/**
 * The DB-outage classifier is the deterministic core of #427: it decides whether a thrown error is
 * the database being unreachable (→ friendly "temporarily unavailable") or something else. Tested
 * with the real Spring/JDBC exception types so no live DB is needed, and with the look-alikes that
 * must NOT be treated as an outage.
 */
class ServiceErrorsTest {

    // ---- outages: must be detected ----

    @Test
    void cannotOpenTransaction_isAnOutage() {
        assertTrue(ServiceErrors.isDatabaseUnavailable(
            new CannotCreateTransactionException("Could not open JPA EntityManager for transaction")));
    }

    @Test
    void resourceFailure_isAnOutage() {
        assertTrue(ServiceErrors.isDatabaseUnavailable(
            new DataAccessResourceFailureException("could not get a connection")));
    }

    @Test
    void hikariPoolTimeout_isAnOutage() {
        assertTrue(ServiceErrors.isDatabaseUnavailable(
            new SQLTransientConnectionException("Connection is not available, request timed out")));
    }

    @Test
    void connectionRefused_deepInTheCauseChain_isAnOutage() {
        Throwable wrapped = new RuntimeException("checkout failed",
            new IllegalStateException("layer", new ConnectException("Connection refused")));
        assertTrue(ServiceErrors.isDatabaseUnavailable(wrapped));
    }

    @Test
    void connectionSqlState08_isAnOutage() {
        assertTrue(ServiceErrors.isDatabaseUnavailable(
            new SQLException("connection failure", "08006")));
    }

    @Test
    void wrappedTransactionException_isStillAnOutage() {
        // Mirrors how services wrap failures (e.g. CheckoutService's "Checkout failed" wrapper).
        Throwable wrapped = new RuntimeException("Sign-in failed",
            new CannotCreateTransactionException("no connection"));
        assertTrue(ServiceErrors.isDatabaseUnavailable(wrapped));
    }

    // ---- look-alikes: must NOT be treated as an outage ----

    @Test
    void constraintViolation_isNotAnOutage() {
        assertFalse(ServiceErrors.isDatabaseUnavailable(
            new DataIntegrityViolationException("duplicate key value violates unique constraint")));
    }

    @Test
    void optimisticLockConflict_isNotAnOutage() {
        assertFalse(ServiceErrors.isDatabaseUnavailable(
            new OptimisticLockingFailureException("row was updated by another transaction")));
    }

    @Test
    void plainBug_isNotAnOutage() {
        assertFalse(ServiceErrors.isDatabaseUnavailable(new IllegalStateException("boom")));
        assertFalse(ServiceErrors.isDatabaseUnavailable(new NullPointerException()));
    }

    @Test
    void nonConnectionSqlState_isNotAnOutage() {
        assertFalse(ServiceErrors.isDatabaseUnavailable(
            new SQLException("syntax error", "42601")));
    }

    @Test
    void nullIsHandledGracefully() {
        assertFalse(ServiceErrors.isDatabaseUnavailable(null));
    }

    // ---- the user-facing message for a failed service call (CWE-209) ----
    // A domain rule's message is written for the user; anything else is an internal failure whose
    // text (SQL, constraint and column names, NPE details) must reach the log and never the screen.

    private static final String INTERNAL_FAILURE_TEXT =
        "could not execute statement [insert into users (email, username) values (?, ?)] constraint uk_x";
    private static final String INTERNAL_FAILURE_MARKER = "uk_x";

    private LogCapture serviceErrorsLog;

    @AfterEach
    void closeLogCapture() {
        if (serviceErrorsLog != null) {
            serviceErrorsLog.close();
        }
    }

    @Test
    void GivenDomainException_WhenUserMessage_ThenItsOwnMessage() {
        String message = ServiceErrors.userMessage(
            new BusinessRuleViolationException("This event is no longer on sale."));

        assertEquals("This event is no longer on sale.", message);
    }

    @Test
    void GivenIllegalArgumentException_WhenUserMessage_ThenItsOwnMessage() {
        String message = ServiceErrors.userMessage(new IllegalArgumentException("Quantity must be positive."));

        assertEquals("Quantity must be positive.", message);
    }

    @Test
    void GivenIllegalStateException_WhenUserMessage_ThenItsOwnMessage() {
        String message = ServiceErrors.userMessage(
            new IllegalStateException("Cannot reconfigure venue map while tickets are reserved or sold"));

        assertEquals("Cannot reconfigure venue map while tickets are reserved or sold", message);
    }

    @Test
    void GivenDomainExceptionWithABlankMessage_WhenUserMessage_ThenTheGenericTextAndTheFailureIsLoggedAtError() {
        serviceErrorsLog = LogCapture.of(ServiceErrors.class);

        String message = ServiceErrors.userMessage(new BusinessRuleViolationException("   "));

        assertEquals(ServiceErrors.GENERIC_FAILURE_MESSAGE, message,
            "a rule violation with no text gives the user nothing to read, so the generic text stands in");
        List<String> logged = serviceErrorsLog.eventsContaining("");
        assertEquals(1, logged.size(), "the failure must be logged once, but was: " + logged);
        assertTrue(logged.get(0).startsWith("ERROR "), "the failure must be logged at ERROR, but was: " + logged);
    }

    @Test
    void GivenNonDomainException_WhenUserMessage_ThenTheGenericTextNotTheRawText() {
        String message = ServiceErrors.userMessage(new DataIntegrityViolationException(INTERNAL_FAILURE_TEXT));

        assertEquals(ServiceErrors.GENERIC_FAILURE_MESSAGE, message,
            "a non-domain failure must show the generic text, but showed: " + message);
    }

    @Test
    void GivenNonDomainException_WhenUserMessage_ThenTheFailureIsLoggedAtError() {
        serviceErrorsLog = LogCapture.of(ServiceErrors.class);

        ServiceErrors.userMessage(new DataIntegrityViolationException(INTERNAL_FAILURE_TEXT));

        List<String> logged = serviceErrorsLog.eventsContaining(INTERNAL_FAILURE_MARKER);
        assertFalse(logged.isEmpty(),
            "the hidden failure must reach the log; recorded " + serviceErrorsLog.eventCount() + " event(s)");
        assertTrue(logged.stream().allMatch(event -> event.startsWith("ERROR ")),
            "the hidden failure must be logged at ERROR, but was: " + logged);
    }

    @Test
    void GivenDatabaseOutage_WhenUserMessage_ThenTheOutageMessage() {
        String message = ServiceErrors.userMessage(new RuntimeException("Sign-in failed",
            new CannotCreateTransactionException("Could not open JPA EntityManager for transaction")));

        assertEquals(ServiceErrors.DB_UNAVAILABLE_MESSAGE, message);
    }
}
