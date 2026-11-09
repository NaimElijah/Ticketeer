package com.ticketing.system.messaging.adapter.out.persistence;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.ticketing.system.messaging.domain.Conversation;
import com.ticketing.system.messaging.domain.ConversationStatus;
import com.ticketing.system.messaging.domain.ConversationType;
import com.ticketing.system.messaging.domain.ParticipantType;

/**
 * Spring Data JPA repository for {@link Conversation} — the auto-implemented SQL backing
 * {@link JpaConversationRepository}. The application layer never sees this type; it depends only on
 * the {@code ConversationRepository} port. Owned messages persist as an ordered
 * {@code @OneToMany} by cascade with the conversation.
 */
public interface SpringDataConversationRepository extends JpaRepository<Conversation, String> {

    /** Derived query: the conversations of one type, in no defined order. */
    List<Conversation> findByType(ConversationType type);

    /** Derived query: the conversations of one type with an initiator of the given kind, in no defined order. */
    List<Conversation> findByTypeAndInitiatorType(ConversationType type, ParticipantType initiatorType);

    /** Derived query: the conversations of one type with one status, in no defined order. */
    List<Conversation> findByTypeAndStatus(ConversationType type, ConversationStatus status);

    /** Derived query: the conversations whose counterparty has that type and id, in no defined order. */
    List<Conversation> findByCounterpartyTypeAndCounterpartyId(ParticipantType counterpartyType, int counterpartyId);

    /**
     * Member Support Inbox: INQUIRY / COMPLAINT the member initiated, plus DIRECT outreach where the
     * member is the counterparty. Mirrors {@code MemoryConversationRepository.findMemberInbox}.
     */
    @Query("select c from Conversation c where "
            + "((c.type = com.ticketing.system.messaging.domain.ConversationType.INQUIRY "
            + "  or c.type = com.ticketing.system.messaging.domain.ConversationType.COMPLAINT) "
            + " and c.initiatorType = com.ticketing.system.messaging.domain.ParticipantType.MEMBER "
            + " and c.initiatorId = :memberId) "
            + "or (c.type = com.ticketing.system.messaging.domain.ConversationType.DIRECT "
            + " and c.counterpartyType = com.ticketing.system.messaging.domain.ParticipantType.MEMBER "
            + " and c.counterpartyId = :memberId)")
    List<Conversation> findMemberInbox(@Param("memberId") int memberId);

    /** Conversations where the participant is the initiator or the counterparty. */
    @Query("select c from Conversation c where "
            + "(c.initiatorId = :pid and c.initiatorType = :ptype) or "
            + "(c.counterpartyId = :pid and c.counterpartyType = :ptype)")
    List<Conversation> findByParticipant(@Param("pid") int participantId,
                                         @Param("ptype") ParticipantType participantType);
}
