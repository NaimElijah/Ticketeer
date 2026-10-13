package com.ticketing.system.unit.infrastructure.persistence.ConversationPersistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.messaging.domain.Conversation;
import com.ticketing.system.messaging.domain.ConversationStatus;
import com.ticketing.system.messaging.domain.ConversationType;
import com.ticketing.system.messaging.application.port.out.ConversationRepository;
import com.ticketing.system.messaging.domain.Message;
import com.ticketing.system.messaging.domain.ParticipantType;

/**
 * Contract every {@link ConversationRepository} implementation must satisfy. The Memory and JPA
 * adapters each subclass this with their own {@link #newRepository()} factory; the tests are reused.
 * The messages/read-state/unread-count tests pin the acceptance: the ordered message thread, the
 * per-message read flag, and the derived unread count survive save/reload on both adapters.
 */
abstract class IConversationRepositoryContractTest {

    protected abstract ConversationRepository newRepository();

    private ConversationRepository repo;

    @BeforeEach
    void setUp() {
        repo = newRepository();
    }

    /** INQUIRY: member (initiator) -> company (counterparty), first message from the member. */
    private Conversation inquiry(int memberId, int companyId, String subject, String firstBody) {
        return Conversation.start(ConversationType.INQUIRY, memberId, ParticipantType.MEMBER,
                companyId, ParticipantType.COMPANY, subject, firstBody);
    }

    private Set<String> ids(List<Conversation> cs) {
        return cs.stream().map(Conversation::getConversationId).collect(Collectors.toSet());
    }

    @Test
    void save_thenFindById_returnsTheConversation() {
        Conversation c = inquiry(5, 10, "Help", "hello");
        repo.save(c);

        Conversation found = repo.findById(c.getConversationId()).orElseThrow();
        assertEquals(ConversationType.INQUIRY, found.getType());
        assertEquals(5, found.getInitiatorId());
        assertEquals(10, found.getCounterpartyId());
        assertEquals(ParticipantType.COMPANY, found.getCounterpartyType());
    }

    @Test
    void findById_emptyWhenMissingOrNull() {
        assertFalse(repo.findById("ghost").isPresent());
        assertFalse(repo.findById(null).isPresent());
    }

    @Test
    void findByParticipant_findsAsInitiatorAndAsCounterparty() {
        Conversation c = inquiry(5, 10, "Help", "hello");
        repo.save(c);

        assertEquals(Set.of(c.getConversationId()), ids(repo.findByParticipant(5, ParticipantType.MEMBER)));
        assertEquals(Set.of(c.getConversationId()), ids(repo.findByParticipant(10, ParticipantType.COMPANY)));
        assertTrue(repo.findByParticipant(99, ParticipantType.MEMBER).isEmpty());
    }

    @Test
    void findByType_andFindByCompanyAsCounterparty_andFindByTypeAndStatus() {
        Conversation c = inquiry(5, 10, "Help", "hello");
        repo.save(c);

        assertEquals(Set.of(c.getConversationId()), ids(repo.findByType(ConversationType.INQUIRY)));
        assertTrue(repo.findByType(ConversationType.COMPLAINT).isEmpty());

        assertEquals(Set.of(c.getConversationId()), ids(repo.findByCompanyAsCounterparty(10)));
        assertTrue(repo.findByCompanyAsCounterparty(999).isEmpty());

        assertEquals(Set.of(c.getConversationId()),
                ids(repo.findByTypeAndStatus(ConversationType.INQUIRY, ConversationStatus.OPEN)));
        assertTrue(repo.findByTypeAndStatus(ConversationType.INQUIRY, ConversationStatus.CLOSED).isEmpty());
    }

    @Test
    void save_persistsMessagesInOrderWithReadState() {
        Conversation c = inquiry(5, 10, "Help", "first");
        c.addMessage(10, ParticipantType.COMPANY, "reply");
        repo.save(c);

        Conversation found = repo.findById(c.getConversationId()).orElseThrow();
        List<Message> messages = found.getMessages();
        assertEquals(2, messages.size());
        assertEquals("first", messages.get(0).getBody());   // @OrderColumn preserves send order
        assertEquals("reply", messages.get(1).getBody());
        assertFalse(messages.get(1).isRead());

        // member 5 reads the company's reply, then we persist and reload
        found.markMessageRead(messages.get(1).getMessageId(), 5);
        repo.save(found);
        assertTrue(repo.findById(c.getConversationId()).orElseThrow().getMessages().get(1).isRead());
    }

    @Test
    void countUnreadForMember_derivesFromMessageReadState() {
        Conversation c = inquiry(5, 10, "Help", "from member");
        c.addMessage(10, ParticipantType.COMPANY, "reply"); // company -> member: unread for member 5
        repo.save(c);

        assertEquals(1, repo.countUnreadForMember(5)); // the company reply
        assertEquals(0, repo.countUnreadForMember(10)); // 10 is the company, not a MEMBER participant

        String replyId = c.getMessages().get(1).getMessageId();
        c.markMessageRead(replyId, 5);
        repo.save(c);
        assertEquals(0, repo.countUnreadForMember(5));
    }
}
