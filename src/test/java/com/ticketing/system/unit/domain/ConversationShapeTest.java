package com.ticketing.system.unit.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import com.ticketing.system.messaging.domain.Conversation;
import com.ticketing.system.messaging.domain.ConversationStatus;
import com.ticketing.system.messaging.domain.ConversationType;
import com.ticketing.system.messaging.domain.Message;
import com.ticketing.system.messaging.domain.ParticipantType;

// ---- Conversation and Message let callers break their shape (#187) ----
//
// The public constructor must leave a Conversation in a shape that Conversation.start and addMessage
// could have produced: its own copy of a non-empty list, opened by the initiator, with every message
// from one of the two sides, and a complaint answered at most once and only by an admin.
class ConversationShapeTest {

    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2027, 7, 15, 17, 0);
    private static final LocalDateTime REPLIED_AT = CREATED_AT.plusMinutes(5);
    private static final int MEMBER_ID = 10;
    private static final int COMPANY_ID = 5;
    private static final int ADMIN_ID = 1;
    private static final int OTHER_ADMIN_ID = 2;
    private static final int OUTSIDER_ID = 99;
    private static final int GROUP_SENTINEL = 0;

    @Test
    void GivenAListTheCallerChangesAfterwards_WhenAConversationIsBuiltFromIt_ThenTheConversationDoesNotChange() {
        Message opener = message("m1", MEMBER_ID, ParticipantType.MEMBER, CREATED_AT);
        List<Message> callersList = new ArrayList<>(List.of(opener));
        Conversation conversation = inquiry(callersList);

        callersList.add(message("m2", COMPANY_ID, ParticipantType.COMPANY, REPLIED_AT));
        callersList.remove(opener);

        List<String> ids = conversation.getMessages().stream().map(Message::getMessageId).toList();
        assertEquals(List.of("m1"), ids, "the conversation keeps its own copy of the messages it was built from");
    }

    @Test
    void GivenAWellFormedList_WhenAConversationIsBuilt_ThenItIsAccepted() {
        Conversation conversation = inquiry(List.of(
                message("m1", MEMBER_ID, ParticipantType.MEMBER, CREATED_AT),
                message("m2", COMPANY_ID, ParticipantType.COMPANY, REPLIED_AT)));

        assertEquals(2, conversation.getMessages().size());
    }

    @Test
    void GivenNoMessages_WhenAConversationIsBuilt_ThenRefused() {
        assertRefused(() -> inquiry(new ArrayList<>()), "a conversation always opens with its first message");
    }

    @Test
    void GivenANullMessage_WhenAConversationIsBuilt_ThenRefused() {
        List<Message> withNull = new ArrayList<>(Arrays.asList(
                message("m1", MEMBER_ID, ParticipantType.MEMBER, CREATED_AT), null));

        assertRefused(() -> inquiry(withNull), "every element is a message");
    }

    @Test
    void GivenAFirstMessageFromTheCounterparty_WhenAConversationIsBuilt_ThenRefused() {
        assertRefused(() -> inquiry(List.of(message("m1", COMPANY_ID, ParticipantType.COMPANY, CREATED_AT))),
                "the initiator writes the first message");
    }

    @Test
    void GivenAMessageFromANonParticipant_WhenAConversationIsBuilt_ThenRefused() {
        assertRefused(() -> inquiry(List.of(
                        message("m1", MEMBER_ID, ParticipantType.MEMBER, CREATED_AT),
                        message("m2", OUTSIDER_ID, ParticipantType.MEMBER, REPLIED_AT))),
                "every message comes from one of the two sides");
    }

    @Test
    void GivenAComplaintWithAMemberReply_WhenAConversationIsBuilt_ThenRefused() {
        assertRefused(() -> complaint(List.of(
                        message("m1", MEMBER_ID, ParticipantType.MEMBER, CREATED_AT),
                        message("m2", MEMBER_ID, ParticipantType.MEMBER, REPLIED_AT))),
                "a complaint accepts no reply from the member");
    }

    @Test
    void GivenAComplaintWithTwoAdminReplies_WhenAConversationIsBuilt_ThenRefused() {
        assertRefused(() -> complaint(List.of(
                        message("m1", MEMBER_ID, ParticipantType.MEMBER, CREATED_AT),
                        message("m2", ADMIN_ID, ParticipantType.ADMIN, REPLIED_AT),
                        message("m3", OTHER_ADMIN_ID, ParticipantType.ADMIN, REPLIED_AT.plusMinutes(1)))),
                "a complaint accepts a single admin response");
    }

    // Each message has the effect addMessage would give it: a counterparty reply makes the thread
    // RESPONDED, an initiator reply reopens it, and lastMessageAt follows the latest message.
    @Test
    void GivenAListWithACounterpartyReply_WhenAConversationIsBuilt_ThenItIsRespondedAsOfTheReply() {
        Conversation conversation = inquiry(List.of(
                message("m1", MEMBER_ID, ParticipantType.MEMBER, CREATED_AT),
                message("m2", COMPANY_ID, ParticipantType.COMPANY, REPLIED_AT)));

        assertEquals(ConversationStatus.RESPONDED, conversation.getStatus());
        assertEquals(REPLIED_AT, conversation.getLastMessageAt());
    }

    @Test
    void GivenAListEndingWithTheInitiatorsAnswerToAReply_WhenAConversationIsBuilt_ThenItIsOpenAsOfThatAnswer() {
        LocalDateTime answeredAt = REPLIED_AT.plusMinutes(1);

        Conversation conversation = inquiry(List.of(
                message("m1", MEMBER_ID, ParticipantType.MEMBER, CREATED_AT),
                message("m2", COMPANY_ID, ParticipantType.COMPANY, REPLIED_AT),
                message("m3", MEMBER_ID, ParticipantType.MEMBER, answeredAt)));

        assertEquals(ConversationStatus.OPEN, conversation.getStatus());
        assertEquals(answeredAt, conversation.getLastMessageAt());
    }

    // The class refuses a bad shape with IllegalStateException from checkInvariants; a constructor
    // check would naturally throw IllegalArgumentException. Either is a refusal; anything else
    // (a NullPointerException, say) is an accident, not a rule.
    private static void assertRefused(Executable build, String rule) {
        RuntimeException refusal = assertThrows(RuntimeException.class, build, () -> "expected a refusal: " + rule);
        assertTrue(refusal instanceof IllegalArgumentException || refusal instanceof IllegalStateException,
                () -> "expected IllegalArgumentException or IllegalStateException (" + rule + "), was: " + refusal);
    }

    private static Conversation inquiry(List<Message> messages) {
        return new Conversation("c1", ConversationType.INQUIRY,
                MEMBER_ID, ParticipantType.MEMBER, COMPANY_ID, ParticipantType.COMPANY,
                "Parking?", CREATED_AT, messages);
    }

    private static Conversation complaint(List<Message> messages) {
        return new Conversation("c1", ConversationType.COMPLAINT,
                MEMBER_ID, ParticipantType.MEMBER, GROUP_SENTINEL, ParticipantType.ADMIN_GROUP,
                "Refund", CREATED_AT, messages);
    }

    private static Message message(String id, int senderId, ParticipantType senderType, LocalDateTime sentAt) {
        return new Message(id, senderId, senderType, "body of " + id, sentAt);
    }
}
