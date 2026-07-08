package com.ticketing.system.unit.presentation;
import com.ticketing.system.sales.application.service.CheckoutService;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ConnectException;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;

import com.ticketing.system.ui.support.ServiceErrors;

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
}
