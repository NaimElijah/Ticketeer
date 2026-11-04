package com.ticketing.system.unit.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import com.ticketing.system.shared.exception.BusinessRuleViolationException;
import com.ticketing.system.shared.exception.ConversationClosedException;
import com.ticketing.system.shared.exception.InvalidParticipantException;
import com.ticketing.system.shared.exception.InvalidStateTransitionException;
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
    private static final int SHARED_ID = 3;
    // conversations.subject is varchar(255) (V1__baseline_schema.sql); a longer subject must be
    // refused by the domain, not by the database insert (#186).
    private static final int SUBJECT_LIMIT = 255;
    private static final LocalDateTime STARTED_AT = LocalDateTime.of(2027, 7, 15, 17, 0);

    private Conversation inquiry() {
        return track(Conversation.start(ConversationType.INQUIRY,
                MEMBER_ID, ParticipantType.MEMBER,
                COMPANY_ID, ParticipantType.COMPANY,
                "Parking?", "Is there parking near the venue?", LocalDateTime.now()));
    }

    private Conversation complaint() {
        return track(Conversation.start(ConversationType.COMPLAINT,
                MEMBER_ID, ParticipantType.MEMBER,
                0, ParticipantType.ADMIN_GROUP,
                "Refund", "Where is my refund?", LocalDateTime.now()));
    }

    @Test
    void GivenAMemberAndACompany_WhenStart_ThenOpensWithFirstMessage() {
        Conversation c = inquiry();
        assertEquals(ConversationStatus.OPEN, c.getStatus());
        assertEquals(1, c.getMessages().size());
        assertEquals(c.getCreatedAt(), c.getLastMessageAt());
    }

    @Test
    void GivenAnInquiry_WhenAddMessage_ThenAppendsAndLastMessageAtIsNotBeforeCreatedAt() {
        Conversation c = inquiry();
        c.addMessage(COMPANY_ID, ParticipantType.COMPANY, "Yes — there is a paid lot nearby.", LocalDateTime.now());
        assertEquals(2, c.getMessages().size());
        assertFalse(c.getLastMessageAt().isBefore(c.getCreatedAt()));
    }

    @Test
    void GivenAnInquiry_WhenAddMessageByNonParticipant_ThenThrowsInvalidParticipant() {
        Conversation c = inquiry();
        assertThrows(InvalidParticipantException.class,
                () -> c.addMessage(OUTSIDER_ID, ParticipantType.MEMBER, "I'm an outsider", LocalDateTime.now()));
    }

    @Test
    void GivenAClosedInquiry_WhenAddMessage_ThenThrowsConversationClosed() {
        Conversation c = inquiry();
        c.transitionToClosed();
        assertThrows(ConversationClosedException.class,
                () -> c.addMessage(MEMBER_ID, ParticipantType.MEMBER, "still there?", LocalDateTime.now()));
    }

    @Test
    void GivenAResolvedComplaint_WhenAddMessage_ThenThrowsConversationClosed() {
        Conversation c = complaint();
        c.transitionToResolved();
        assertThrows(ConversationClosedException.class,
                () -> c.addMessage(MEMBER_ID, ParticipantType.MEMBER, "another point", LocalDateTime.now()));
    }

    @Test
    void GivenAnOpenInquiry_WhenCounterpartyAndInitiatorReplyInTurn_ThenFlipsOpenToRespondedAndBack() {
        Conversation c = inquiry();
        c.addMessage(COMPANY_ID, ParticipantType.COMPANY, "We replied", LocalDateTime.now());
        assertEquals(ConversationStatus.RESPONDED, c.getStatus());
        c.addMessage(MEMBER_ID, ParticipantType.MEMBER, "Thanks, one more thing", LocalDateTime.now());
        assertEquals(ConversationStatus.OPEN, c.getStatus());
    }

    @Test
    void GivenAComplaint_WhenSpecificAdminReplies_ThenActsForAdminGroup() {
        Conversation c = complaint();
        assertTrue(c.involvesParticipant(ADMIN_ID, ParticipantType.ADMIN));
        c.addMessage(ADMIN_ID, ParticipantType.ADMIN, "Looking into it", LocalDateTime.now());
        assertEquals(ConversationStatus.RESPONDED, c.getStatus());
    }

    // -- one-shot complaint rule ------------------------------------------------

    @Test
    void GivenAComplaint_WhenMemberRepliesAfterOpening_ThenThrows() {
        Conversation c = complaint();  // already carries the member's opening message
        assertThrows(BusinessRuleViolationException.class,
                () -> c.addMessage(MEMBER_ID, ParticipantType.MEMBER, "any update?", LocalDateTime.now()));
    }

    @Test
    void GivenAComplaintWithAnAdminReply_WhenSecondAdminReply_ThenThrows() {
        Conversation c = complaint();
        c.addMessage(ADMIN_ID, ParticipantType.ADMIN, "First and only response", LocalDateTime.now());
        assertThrows(BusinessRuleViolationException.class,
                () -> c.addMessage(ADMIN_ID, ParticipantType.ADMIN, "second response", LocalDateTime.now()));
    }

    @Test
    void GivenAnOutsider_WhenInvolvesParticipant_ThenRejectsUnrelated() {
        Conversation c = inquiry();
        assertFalse(c.involvesParticipant(OUTSIDER_ID, ParticipantType.MEMBER));
    }

    @Test
    void GivenTheMembersOpeningMessage_WhenTheMemberMarksTheThreadRead_ThenItStaysUnreadForTheCompany() {
        Conversation c = inquiry();                                   // 1 message from the member

        c.markReadBy(MEMBER_ID, ParticipantType.MEMBER);

        assertEquals(1, c.unreadCountFor(COMPANY_ID, ParticipantType.COMPANY));
    }

    @Test
    void GivenTheMembersOpeningMessage_WhenTheCompanyMarksTheThreadRead_ThenNothingIsUnreadForTheCompany() {
        Conversation c = inquiry();

        c.markReadBy(COMPANY_ID, ParticipantType.COMPANY);

        assertEquals(0, c.unreadCountFor(COMPANY_ID, ParticipantType.COMPANY));
    }

    @Test
    void GivenAThreadTheCompanyHasRead_WhenItMarksItReadAgain_ThenNothingChanges() {
        Conversation c = inquiry();
        c.markReadBy(COMPANY_ID, ParticipantType.COMPANY);

        c.markReadBy(COMPANY_ID, ParticipantType.COMPANY);

        assertEquals(0, c.unreadCountFor(COMPANY_ID, ParticipantType.COMPANY));
        assertEquals(0, c.unreadCountFor(MEMBER_ID, ParticipantType.MEMBER));
    }

    @Test
    void GivenAnOutsider_WhenMarkReadBy_ThenRefusedAndNothingIsRead() {
        Conversation c = inquiry();

        assertThrows(InvalidParticipantException.class, () -> c.markReadBy(OUTSIDER_ID, ParticipantType.MEMBER));

        assertEquals(1, c.unreadCountFor(COMPANY_ID, ParticipantType.COMPANY));
    }

    @Test
    void GivenOneMessageFromEachSide_WhenUnreadCountFor_ThenExcludesOwnMessages() {
        Conversation c = inquiry();                                   // 1 message from the member
        c.addMessage(COMPANY_ID, ParticipantType.COMPANY, "reply", LocalDateTime.now());   // 1 message from the company
        assertEquals(1, c.unreadCountFor(MEMBER_ID, ParticipantType.MEMBER));    // only the company's message
        assertEquals(1, c.unreadCountFor(COMPANY_ID, ParticipantType.COMPANY));  // only the member's message
    }

    // Member and company ids come from separate sequences that both start at 1, so a company often
    // shares its id with the member it writes to. A message is the reader's own only when both its
    // sender id and its sender type are the reader's.
    @Test
    void GivenACompanyReplyToTheMemberWhoSharesItsId_WhenEachSideCountsUnread_ThenEachCountsTheOthersMessage() {
        Conversation c = inquiryBetweenSharedIds();
        c.addMessage(SHARED_ID, ParticipantType.COMPANY, "Yes, lot B.", STARTED_AT.plusMinutes(1));

        assertEquals(1, c.unreadCountFor(SHARED_ID, ParticipantType.MEMBER), "the company's reply is unread for the member");
        assertEquals(1, c.unreadCountFor(SHARED_ID, ParticipantType.COMPANY), "the member's message is unread for the company");
    }

    @Test
    void GivenAMemberMessageToTheCompanyThatSharesItsId_WhenTheCompanyMarksTheThreadRead_ThenItIsRead() {
        Conversation c = inquiryBetweenSharedIds();

        c.markReadBy(SHARED_ID, ParticipantType.COMPANY);

        assertTrue(c.getMessages().get(0).isRead(), "the company read the member's message");
    }

    @Test
    void GivenAMemberMessageToTheCompanyThatSharesItsId_WhenTheMemberMarksTheThreadRead_ThenTheirOwnMessageStaysUnread() {
        Conversation c = inquiryBetweenSharedIds();

        c.markReadBy(SHARED_ID, ParticipantType.MEMBER);

        assertFalse(c.getMessages().get(0).isRead(), "only the other side reads a message");
    }

    private Conversation inquiryBetweenSharedIds() {
        return track(Conversation.start(ConversationType.INQUIRY, SHARED_ID, ParticipantType.MEMBER,
                SHARED_ID, ParticipantType.COMPANY, "Parking?", "Is there parking near the venue?", STARTED_AT));
    }

    @Test
    void GivenAClosedInquiry_WhenTransitionToResponded_ThenThrows() {
        Conversation c = inquiry();
        c.transitionToClosed();
        assertThrows(InvalidStateTransitionException.class, c::transitionToResponded);
    }

    @Test
    void GivenAClosedInquiry_WhenTransitionToClosed_ThenIsIdempotent() {
        Conversation c = inquiry();
        c.transitionToClosed();
        c.transitionToClosed();
        assertTrue(c.isClosed());
    }

    // -- subject length (#186) --------------------------------------------------

    @Test
    void GivenSubjectOf256Characters_WhenStart_ThenRefusedNamingTheLimit() {
        String subject = "s".repeat(SUBJECT_LIMIT + 1);

        BusinessRuleViolationException ex = assertThrows(BusinessRuleViolationException.class,
                () -> Conversation.start(ConversationType.INQUIRY,
                        MEMBER_ID, ParticipantType.MEMBER, COMPANY_ID, ParticipantType.COMPANY,
                        subject, "Is there parking near the venue?", STARTED_AT));

        assertTrue(ex.getMessage().contains(String.valueOf(SUBJECT_LIMIT)),
                () -> "the refusal names the " + SUBJECT_LIMIT + "-character limit, was: " + ex.getMessage());
    }

    @Test
    void GivenSubjectOf255Characters_WhenStart_ThenAccepted() {
        String subject = "s".repeat(SUBJECT_LIMIT);

        Conversation c = track(Conversation.start(ConversationType.INQUIRY,
                MEMBER_ID, ParticipantType.MEMBER, COMPANY_ID, ParticipantType.COMPANY,
                subject, "Is there parking near the venue?", STARTED_AT));

        assertEquals(subject, c.getSubject());
    }
}
