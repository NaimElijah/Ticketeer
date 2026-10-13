package com.ticketing.system.ui.security;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.ticketing.system.ui.support.ServiceErrors;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.CannotCreateTransactionException;

/**
 * The global handler's user-facing message selection. Shows a DB outage gets the specific
 * "temporarily unavailable" text while anything else gets a generic (still friendly) apology —
 * the toast rendering itself is exercised by the live UI, not unit-tested.
 */
class GlobalUiErrorHandlerTest {

    @Test
    void dbOutage_getsTheTemporarilyUnavailableMessage() {
        assertEquals(ServiceErrors.DB_UNAVAILABLE_MESSAGE,
            GlobalUiErrorHandler.messageFor(new CannotCreateTransactionException("no connection")));
    }

    @Test
    void anyOtherError_getsTheGenericMessage() {
        assertEquals(GlobalUiErrorHandler.GENERIC_MESSAGE,
            GlobalUiErrorHandler.messageFor(new IllegalStateException("boom")));
    }
}
