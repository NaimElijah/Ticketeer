package com.ticketing.system.unit.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import com.ticketing.system.messaging.domain.Message;
import com.ticketing.system.messaging.domain.ParticipantType;
import com.ticketing.system.shared.exception.BusinessRuleViolationException;

// Unit tests for the Message sub-entity of the Conversation aggregate.
class MessageTest {

    // messages.body is varchar(255) (V1__baseline_schema.sql); a longer body must be refused by the
    // domain, not by the database insert (#186).
    private static final int BODY_LIMIT = 255;
    private static final LocalDateTime SENT_AT = LocalDateTime.of(2027, 7, 15, 17, 0);

    private Message sample() {
        return new Message("m1", 10, ParticipantType.MEMBER, "hello", LocalDateTime.now());
    }

    // Marking read is Conversation.markReadBy's job, and markRead is package-private (#187); its flip
    // and idempotence are tested through the conversation in ConversationTest.
    @Test
    void GivenANewMessage_WhenBuilt_ThenItIsUnread() {
        assertFalse(sample().isRead());
    }

    @Test
    void construct_blankBody_throws() {
        assertThrows(IllegalStateException.class,
                () -> new Message("m1", 10, ParticipantType.MEMBER, "   ", LocalDateTime.now()));
    }

    @Test
    void construct_blankId_throws() {
        assertThrows(IllegalStateException.class,
                () -> new Message("", 10, ParticipantType.MEMBER, "hello", LocalDateTime.now()));
    }

    @Test
    void construct_nullSenderType_throws() {
        assertThrows(IllegalStateException.class,
                () -> new Message("m1", 10, null, "hello", LocalDateTime.now()));
    }

    @Test
    void construct_nullSentAt_throws() {
        assertThrows(IllegalStateException.class,
                () -> new Message("m1", 10, ParticipantType.MEMBER, "hello", null));
    }

    @Test
    void GivenBodyOf256Characters_WhenConstruct_ThenRefusedNamingTheLimit() {
        String body = "b".repeat(BODY_LIMIT + 1);

        BusinessRuleViolationException ex = assertThrows(BusinessRuleViolationException.class,
                () -> new Message("m1", 10, ParticipantType.MEMBER, body, SENT_AT));

        assertTrue(ex.getMessage().contains(String.valueOf(BODY_LIMIT)),
                () -> "the refusal names the " + BODY_LIMIT + "-character limit, was: " + ex.getMessage());
    }

    @Test
    void GivenBodyOf255Characters_WhenConstruct_ThenAccepted() {
        String body = "b".repeat(BODY_LIMIT);

        Message m = new Message("m1", 10, ParticipantType.MEMBER, body, SENT_AT);

        assertEquals(body, m.getBody());
    }
}
