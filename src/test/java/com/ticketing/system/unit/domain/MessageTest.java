package com.ticketing.system.unit.domain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import com.ticketing.system.messaging.domain.Message;
import com.ticketing.system.messaging.domain.ParticipantType;

// Unit tests for the Message sub-entity of the Conversation aggregate.
class MessageTest {

    private Message sample() {
        return new Message("m1", 10, ParticipantType.MEMBER, "hello", LocalDateTime.now());
    }

    @Test
    void markRead_flipsAndIsIdempotent() {
        Message m = sample();
        assertFalse(m.isRead());
        m.markRead();
        assertTrue(m.isRead());
        m.markRead();
        assertTrue(m.isRead());
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
}
