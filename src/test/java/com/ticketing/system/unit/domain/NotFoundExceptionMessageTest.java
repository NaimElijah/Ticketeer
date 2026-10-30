package com.ticketing.system.unit.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.ticketing.system.shared.exception.EventNotFoundException;
import com.ticketing.system.shared.exception.NullVenueMapException;
import com.ticketing.system.shared.exception.UserNotFoundException;

/**
 * #187: the by-id constructors of the not-found exceptions build the whole message from the id.
 * {@code ServiceErrors.userMessage} shows a {@code DomainException}'s message to the user as is, so
 * it must read as one sentence, never as a prefix doubled onto a caller-built text.
 */
class NotFoundExceptionMessageTest {

    @Test
    void GivenEventId_WhenEventNotFoundExceptionBuilt_ThenMessageIsEventNotFoundWithTheId() {
        assertEquals("Event not found: 5", new EventNotFoundException(5).getMessage());
    }

    @Test
    void GivenEventId_WhenNullVenueMapExceptionBuilt_ThenMessageIsVenueMapNotFoundWithTheId() {
        assertEquals("Venue map not found: 7", new NullVenueMapException(7).getMessage());
    }

    @Test
    void GivenUserId_WhenUserNotFoundExceptionBuilt_ThenMessageIsUserNotFoundWithTheId() {
        assertEquals("User not found: 42", new UserNotFoundException(42).getMessage());
    }
}
