package com.ticketing.system.ui.support;

import java.net.ConnectException;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLTransientConnectionException;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.CannotCreateTransactionException;

/**
 * Classifies presentation-facing errors. The one question it answers today: is a thrown error the
 * database / persistence layer being unreachable (DB down, connection refused, pool timed out) — as
 * opposed to a bug or a violated domain rule? The login flow uses this to show "the service is
 * temporarily unavailable" instead of "sign-in failed", and the global error handler uses it to keep
 * a DB outage friendly anywhere in the UI.
 *
 * <p>Deliberately narrow: only connectivity / resource failures count. Constraint violations,
 * optimistic-lock conflicts and empty results are {@code DataAccessException}s too, but they are NOT
 * outages — counting them would mislabel a real bug as "try again later", so they are excluded.
 */
public final class ServiceErrors {

    /** Single source of truth for the user-facing outage message (login view + global handler). */
    public static final String DB_UNAVAILABLE_MESSAGE =
        "The service is temporarily unavailable — please try again in a moment.";

    private ServiceErrors() { }

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
