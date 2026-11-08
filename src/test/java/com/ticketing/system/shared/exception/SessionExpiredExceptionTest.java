package com.ticketing.system.shared.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import org.junit.jupiter.api.Test;

import com.ticketing.system.ui.support.ServiceErrors;

/**
 * {@link SessionExpiredException} is a dead token like any other, so every caller that catches
 * {@link InvalidTokenException} handles an expiry too (#122). Its message stays its own: the parent's
 * wording names a bad token, and an expired session must not read as one wherever it is shown.
 */
class SessionExpiredExceptionTest {

    private static final String EXPIRY_MESSAGE = "Session expired; please re-authenticate";

    @Test
    void GivenAnExpiredSession_WhenCaughtAsADeadToken_ThenItIsAnInvalidTokenException() {
        assertInstanceOf(InvalidTokenException.class, new SessionExpiredException());
    }

    @Test
    void GivenAnExpiredSession_WhenItsMessageIsRead_ThenItIsTheExpiryWording() {
        assertEquals(EXPIRY_MESSAGE, new SessionExpiredException().getMessage());
    }

    @Test
    void GivenAnExpiredSession_WhenPrinted_ThenItCarriesTheExpiryWording() {
        assertEquals(SessionExpiredException.class.getName() + ": " + EXPIRY_MESSAGE,
                new SessionExpiredException().toString());
    }

    @Test
    void GivenAnExpiredSession_WhenShownToTheUser_ThenTheUserReadsTheExpiryWording() {
        assertEquals(EXPIRY_MESSAGE, ServiceErrors.userMessage(new SessionExpiredException()));
    }
}
