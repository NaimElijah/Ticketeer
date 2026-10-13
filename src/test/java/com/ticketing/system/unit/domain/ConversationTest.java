package com.ticketing.system.unit.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.ticketing.system.shared.exception.BusinessRuleViolationException;
import com.ticketing.system.shared.exception.ConversationClosedException;
import com.ticketing.system.shared.exception.InvalidParticipantException;
import com.ticketing.system.shared.exception.InvalidStateTransitionException;
import com.ticketing.system.shared.exception.MessageNotFoundException;
import com.ticketing.system.messaging.domain.Conversation;
import com.ticketing.system.messaging.domain.ConversationStatus;
import com.ticketing.system.messaging.domain.ConversationType;
import com.ticketing.system.messaging.domain.ParticipantType;
import com.ticketing.system.support.BaseDomainTest;

// Unit tests for the centralized messaging Conversation aggregate.
class ConversationTest extends BaseDomainTest {

    private static final int MEMBER_ID = 10;
    private static final int COMPANY_ID = 5;
    private static final int ADMIN_ID = 7;
    private static final int OUTSIDER_ID = 99;

    private Conversation inquiry() {
        return track(Conversation.start(ConversationType.INQUIRY,
                MEMBER_ID, ParticipantType.MEMBER,
                COMPANY_ID, ParticipantType.COMPANY,
                "Parking?", "Is there parking near the venue?"));
    }

    private Conversation complaint() {
        return track(Conversation.start(ConversationType.COMPLAINT,
                MEMBER_ID, ParticipantType.MEMBER,
                0, ParticipantType.ADMIN_GROUP,
                "Refund", "Where is my refund?"));
    }

    @Test
    void start_opensWithFirstMessage() {
        Conversation c = inquiry();
        assertEquals(ConversationStatus.OPEN, c.getStatus());
        assertEquals(1, c.getMessages().size());
        assertEquals(c.getCreatedAt(), c.getLastMessageAt());
    }

    @Test
    void addMessage_appendsAndUpdatesTimestamp() {
        Conversation c = inquiry();
        c.addMessage(COMPANY_ID, ParticipantType.COMPANY, "Yes — there is a paid lot nearby.");
        assertEquals(2, c.getMessages().size());
        assertFalse(c.getLastMessageAt().isBefore(c.getCreatedAt()));
    }

    @Test
    void addMessage_byNonParticipant_throwsInvalidParticipant() {
        Conversation c = inquiry();
        assertThrows(InvalidParticipantException.class,
                () -> c.addMessage(OUTSIDER_ID, ParticipantType.MEMBER, "I'm an outsider"));
    }

    @Test
    void addMessage_onClosed_throwsConversationClosed() {
        Conversation c = inquiry();
        c.transitionToClosed();
        assertThrows(ConversationClosedException.class,
                () -> c.addMessage(MEMBER_ID, ParticipantType.MEMBER, "still there?"));
    }

    @Test
    void addMessage_onResolved_throwsConversationClosed() {
        Conversation c = complaint();
        c.transitionToResolved();
        assertThrows(ConversationClosedException.class,
                () -> c.addMessage(MEMBER_ID, ParticipantType.MEMBER, "another point"));
    }

    @Test
    void counterpartyReply_flipsOpenToResponded_andInitiatorReplyFlipsBack() {
        Conversation c = inquiry();
        c.addMessage(COMPANY_ID, ParticipantType.COMPANY, "We replied");
        assertEquals(ConversationStatus.RESPONDED, c.getStatus());
        c.addMessage(MEMBER_ID, ParticipantType.MEMBER, "Thanks, one more thing");
        assertEquals(ConversationStatus.OPEN, c.getStatus());
    }

    @Test
    void specificAdmin_actsForAdminGroup_onComplaint() {
        Conversation c = complaint();
        assertTrue(c.involvesParticipant(ADMIN_ID, ParticipantType.ADMIN));
        c.addMessage(ADMIN_ID, ParticipantType.ADMIN, "Looking into it");
        assertEquals(ConversationStatus.RESPONDED, c.getStatus());
    }

    // -- one-shot complaint rule ------------------------------------------------

    @Test
    void complaint_memberCannotReplyAfterOpening_throws() {
        Conversation c = complaint();  // already carries the member's opening message
        assertThrows(BusinessRuleViolationException.class,
                () -> c.addMessage(MEMBER_ID, ParticipantType.MEMBER, "any update?"));
    }

    @Test
    void complaint_secondAdminReply_throws() {
        Conversation c = complaint();
        c.addMessage(ADMIN_ID, ParticipantType.ADMIN, "First and only response");
        assertThrows(BusinessRuleViolationException.class,
                () -> c.addMessage(ADMIN_ID, ParticipantType.ADMIN, "second response"));
    }

    @Test
    void involvesParticipant_rejectsUnrelated() {
        Conversation c = inquiry();
        assertFalse(c.involvesParticipant(OUTSIDER_ID, ParticipantType.MEMBER));
    }

    @Test
    void markMessageRead_onlyByNonSender_andIsIdempotent() {
        Conversation c = inquiry();
        String firstId = c.getMessages().get(0).getMessageId(); // authored by the member
        // The sender marking their own message is a no-op (stays unread for the recipient).
        c.markMessageRead(firstId, MEMBER_ID);
        assertEquals(1, c.unreadCountFor(COMPANY_ID));
        // The recipient marks it read.
        c.markMessageRead(firstId, COMPANY_ID);
        assertEquals(0, c.unreadCountFor(COMPANY_ID));
        // Idempotent.
        c.markMessageRead(firstId, COMPANY_ID);
        assertEquals(0, c.unreadCountFor(COMPANY_ID));
    }

    @Test
    void markMessageRead_missingMessage_throws() {
        Conversation c = inquiry();
        assertThrows(MessageNotFoundException.class,
                () -> c.markMessageRead("no-such-id", COMPANY_ID));
    }

    @Test
    void unreadCountFor_excludesOwnMessages() {
        Conversation c = inquiry();                                   // 1 message from the member
        c.addMessage(COMPANY_ID, ParticipantType.COMPANY, "reply");   // 1 message from the company
        assertEquals(1, c.unreadCountFor(MEMBER_ID));   // only the company's message
        assertEquals(1, c.unreadCountFor(COMPANY_ID));  // only the member's message
    }

    @Test
    void transitionToResponded_onClosed_throws() {
        Conversation c = inquiry();
        c.transitionToClosed();
        assertThrows(InvalidStateTransitionException.class, c::transitionToResponded);
    }

    @Test
    void transitionToClosed_isIdempotent() {
        Conversation c = inquiry();
        c.transitionToClosed();
        c.transitionToClosed();
        assertTrue(c.isClosed());
    }
}
