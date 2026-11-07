package com.ticketing.system.unit.infrastructure.persistence.ConversationPersistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.ticketing.system.messaging.domain.Conversation;
import com.ticketing.system.messaging.domain.ConversationStatus;
import com.ticketing.system.messaging.domain.ConversationType;
import com.ticketing.system.messaging.application.port.out.ConversationRepository;
import com.ticketing.system.messaging.domain.Message;
import com.ticketing.system.messaging.domain.ParticipantType;
import com.ticketing.system.testutil.RepositoryLockProbe;

/**
 * Contract every {@link ConversationRepository} implementation must satisfy. The Memory and JPA
 * adapters each subclass this with their own {@link #newRepository()} factory; the tests are reused.
 * The messages/read-state test pins the acceptance: the ordered message thread and the
 * per-message read flag survive save/reload on both adapters.
 */
abstract class IConversationRepositoryContractTest {

    private static final LocalDateTime STARTED_AT = LocalDateTime.of(2027, 7, 15, 17, 0);

    protected abstract ConversationRepository newRepository();

    private ConversationRepository repo;

    @BeforeEach
    void setUp() {
        repo = newRepository();
    }

    /** INQUIRY: member (initiator) -> company (counterparty), first message from the member. */
    private Conversation inquiry(int memberId, int companyId, String subject, String firstBody) {
        return Conversation.start(ConversationType.INQUIRY, memberId, ParticipantType.MEMBER,
                companyId, ParticipantType.COMPANY, subject, firstBody, STARTED_AT);
    }

    private Set<String> ids(List<Conversation> cs) {
        return cs.stream().map(Conversation::getConversationId).collect(Collectors.toSet());
    }

    @Test
    void GivenASavedConversation_WhenFindById_ThenReturnsTheConversation() {
        Conversation c = inquiry(5, 10, "Help", "hello");
        repo.save(c);

        Conversation found = repo.findById(c.getConversationId()).orElseThrow();
        assertEquals(ConversationType.INQUIRY, found.getType());
        assertEquals(5, found.getInitiatorId());
        assertEquals(10, found.getCounterpartyId());
        assertEquals(ParticipantType.COMPANY, found.getCounterpartyType());
    }

    @Test
    void GivenAMissingOrNullId_WhenFindById_ThenIsEmpty() {
        assertFalse(repo.findById("ghost").isPresent());
        assertFalse(repo.findById(null).isPresent());
    }

    @Test
    void GivenASavedConversation_WhenFindByParticipant_ThenFindsAsInitiatorAndAsCounterpartyButNotForAnotherMember() {
        Conversation c = inquiry(5, 10, "Help", "hello");
        repo.save(c);

        assertEquals(Set.of(c.getConversationId()), ids(repo.findByParticipant(5, ParticipantType.MEMBER)));
        assertEquals(Set.of(c.getConversationId()), ids(repo.findByParticipant(10, ParticipantType.COMPANY)));
        assertTrue(repo.findByParticipant(99, ParticipantType.MEMBER).isEmpty());
    }

    @Test
    void GivenASavedOpenInquiry_WhenFindByTypeOrCompanyAsCounterpartyOrTypeAndStatus_ThenReturnsItOnlyForMatchingArguments() {
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

    // The Jpa tables hold the type, both participant types, the status and each message's sender type
    // under check constraints that a migration must widen for each new constant (ADR-007). Inherited, so
    // each save commits and a missing value fails here.
    @ParameterizedTest
    @EnumSource(ParticipantType.class)
    void GivenAConversationWhoseParticipantsAreOfEachType_WhenSaved_ThenTheTypesComeBack(ParticipantType type) {
        Conversation c = Conversation.start(ConversationType.DIRECT, 5, type, 10, type, "Hi", "hello", STARTED_AT);
        repo.save(c);

        Conversation found = repo.findById(c.getConversationId()).orElseThrow();
        assertEquals(List.of(type, type, type),
                List.of(found.getInitiatorType(), found.getCounterpartyType(), found.getMessages().get(0).getSenderType()));
    }

    @ParameterizedTest
    @EnumSource(ConversationType.class)
    void GivenAConversationOfEachType_WhenSaved_ThenItIsFoundByThatType(ConversationType type) {
        Conversation c = Conversation.start(type, 5, ParticipantType.MEMBER, 10, ParticipantType.COMPANY,
                "Hi", "hello", STARTED_AT);
        repo.save(c);

        assertEquals(Set.of(c.getConversationId()), ids(repo.findByType(type)));
    }

    @Test
    void GivenConversationsInEveryStatus_WhenSaved_ThenEachKeepsItsStatus() {
        Conversation open = inquiry(5, 10, "Open", "hello");
        Conversation responded = inquiry(5, 10, "Responded", "hello");
        responded.transitionToResponded();
        Conversation resolved = inquiry(5, 10, "Resolved", "hello");
        resolved.transitionToResolved();
        Conversation closed = inquiry(5, 10, "Closed", "hello");
        closed.transitionToClosed();
        List.of(open, responded, resolved, closed).forEach(repo::save);

        Map<String, ConversationStatus> stored = repo.findByCompanyAsCounterparty(10).stream()
                .collect(Collectors.toMap(Conversation::getSubject, Conversation::getStatus));

        assertEquals(Map.of("Open", ConversationStatus.OPEN, "Responded", ConversationStatus.RESPONDED,
                "Resolved", ConversationStatus.RESOLVED, "Closed", ConversationStatus.CLOSED), stored);
    }

    @Test
    void GivenAConversationWithAReply_WhenSave_ThenPersistsMessagesInOrderWithReadState() {
        Conversation c = inquiry(5, 10, "Help", "first");
        c.addMessage(10, ParticipantType.COMPANY, "reply", STARTED_AT);
        repo.save(c);

        Conversation found = repo.findById(c.getConversationId()).orElseThrow();
        List<Message> messages = found.getMessages();
        assertEquals(2, messages.size());
        assertEquals("first", messages.get(0).getBody());   // @OrderColumn preserves send order
        assertEquals("reply", messages.get(1).getBody());
        assertFalse(messages.get(1).isRead());

        // member 5 reads the company's reply, then we persist and reload
        found.markReadBy(5, ParticipantType.MEMBER);
        repo.save(found);
        assertTrue(repo.findById(c.getConversationId()).orElseThrow().getMessages().get(1).isRead());
    }

    // IRepository's lock contract: reentrant, and released by one unlock per lock. The second
    // lockForUpdate would hang a non-reentrant lock, hence the timeout on a separate thread. The Jpa
    // adapter makes both calls no-ops (@Version guards its writes), so it passes trivially.
    @Test
    @Timeout(value = RepositoryLockProbe.TEST_TIMEOUT_SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void GivenALockTakenTwiceAndReleasedTwice_WhenAnotherThreadLocksTheSameId_ThenItGetsTheLock() throws Exception {
        repo.lockForUpdate("conversation-1");
        repo.lockForUpdate("conversation-1");
        repo.unlock("conversation-1");
        repo.unlock("conversation-1");

        RepositoryLockProbe.assertAnotherThreadCanLock(repo, "conversation-1");
    }
}
