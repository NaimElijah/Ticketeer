package com.ticketing.system.ui.support;

import java.net.ConnectException;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLTransientConnectionException;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.CannotCreateTransactionException;

import com.ticketing.system.shared.exception.DomainException;

import lombok.extern.slf4j.Slf4j;

/**
 * Classifies presentation-facing errors. The first question it answers: is a thrown error the
 * database / persistence layer being unreachable (DB down, connection refused, pool timed out) — as
 * opposed to a bug or a violated domain rule? The login flow uses this to show "the service is
 * temporarily unavailable" instead of "sign-in failed", and the global error handler uses it to keep
 * a DB outage friendly anywhere in the UI.
 *
 * <p>Deliberately narrow: only connectivity / resource failures count. Constraint violations,
 * optimistic-lock conflicts and empty results are {@code DataAccessException}s too, but they are NOT
 * outages — counting them would mislabel a real bug as "try again later", so they are excluded.
 *
 * <p>The second, answered by {@link #userMessage(RuntimeException)}: which text may a presenter show
 * for a failed service call? Business rules are signalled with {@link DomainException} and, in much
 * of the code, with {@link IllegalArgumentException} / {@link IllegalStateException}, so all three
 * pass their message through. Retyping those rule violations as {@code DomainException} would let
 * the helper narrow to domain exceptions alone.
 */
@Slf4j
public final class ServiceErrors {

    /** Single source of truth for the user-facing outage message (login view + global handler). */
    public static final String DB_UNAVAILABLE_MESSAGE =
        "The service is temporarily unavailable — please try again in a moment.";

    /**
     * What the user sees for an internal failure, whose own text stays in the log. The same wording
     * as {@code GlobalUiErrorHandler}'s for an exception no presenter caught.
     */
    public static final String GENERIC_FAILURE_MESSAGE = "Something went wrong — please try again.";

    private ServiceErrors() { }

    /**
     * The text a presenter may show the user for a failed service call.
     *
     * <ul>
     *   <li>A database outage anywhere in the cause chain gives {@link #DB_UNAVAILABLE_MESSAGE}.</li>
     *   <li>A {@link DomainException}, {@link IllegalArgumentException} or
     *       {@link IllegalStateException} gives its own message: the services signal business rules
     *       with all three (for example "Cannot reconfigure venue map while tickets are reserved or
     *       sold"), and hiding those would leave the user without a reason.</li>
     *   <li>Anything else (a Spring {@code DataAccessException}, a JPA {@code PersistenceException}, a
     *       {@code NullPointerException}, a {@code TransactionSystemException}, any other
     *       {@code RuntimeException}) is an internal failure. Its text can carry SQL, table, column and
     *       constraint names, so it is logged at ERROR with the exception and the user gets
     *       {@link #GENERIC_FAILURE_MESSAGE}. So does a passed-through exception without a message.</li>
     * </ul>
     *
     * @param error the exception the service call threw; not {@code null}
     * @return the text to show the user, never {@code null}
     */
    public static String userMessage(RuntimeException error) {
        if (isDatabaseUnavailable(error)) {
            return DB_UNAVAILABLE_MESSAGE;
        }
        if (isUserFacing(error) && error.getMessage() != null && !error.getMessage().isBlank()) {
            return error.getMessage();
        }
        log.error("Service call failed with an internal error; the user sees the generic message", error);
        return GENERIC_FAILURE_MESSAGE;
    }

    private static boolean isUserFacing(RuntimeException error) {
        return error instanceof DomainException
            || error instanceof IllegalArgumentException
            || error instanceof IllegalStateException;
    }

    /** True when {@code error} (or anything in its cause chain) is the database being unreachable. */
    public static boolean isDatabaseUnavailable(Throwable error) {
        for (Throwable cause = error; cause != null; cause = nextCause(cause)) {
            if (cause instanceof CannotCreateTransactionException        // couldn't open a tx (no connection)
                    || cause instanceof DataAccessResourceFailureException // Spring "resource failed" translation
                    || cause instanceof SQLTransientConnectionException   // HikariCP pool timed out
                    || cause instanceof SQLNonTransientConnectionException
                    || cause instanceof ConnectException) {               // TCP connection refused (dead host/port)
                return true;
            }
            if (cause instanceof SQLException sql && isConnectionSqlState(sql.getSQLState())) {
                return true;
            }
            if (cause.getClass().getSimpleName().equals("JDBCConnectionException")) {
                return true; // Hibernate connectivity error — matched by name to avoid a hard dependency
            }
        }
        return false;
    }

    // SQLState class "08" = connection exception (SQL standard); Postgres uses 57P0x for admin shutdown.
    private static boolean isConnectionSqlState(String sqlState) {
        return sqlState != null && (sqlState.startsWith("08") || sqlState.startsWith("57P"));
    }

    private static Throwable nextCause(Throwable t) {
        Throwable cause = t.getCause();
        return cause == t ? null : cause; // guard a self-referential cause chain
    }
}
