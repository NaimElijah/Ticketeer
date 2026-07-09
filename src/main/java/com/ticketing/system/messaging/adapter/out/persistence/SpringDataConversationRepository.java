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
 * the {@code ConversationRepository} domain port. Owned messages persist as an ordered
 * {@code @OneToMany} by cascade with the conversation.
 */
public interface SpringDataConversationRepository extends JpaRepository<Conversation, String> {

    List<Conversation> findByType(ConversationType type);

    List<Conversation> findByTypeAndInitiatorType(ConversationType type, ParticipantType initiatorType);

    List<Conversation> findByTypeAndStatus(ConversationType type, ConversationStatus status);

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

    /**
     * Unread badge: messages addressed to the member (not sent by them, not yet read) across the
     * conversations where the member is a participant — derived directly from message read-state.
     */
    @Query("select count(m) from Conversation c join c.messages m where "
            + "((c.initiatorType = com.ticketing.system.messaging.domain.ParticipantType.MEMBER and c.initiatorId = :memberId) or "
            + " (c.counterpartyType = com.ticketing.system.messaging.domain.ParticipantType.MEMBER and c.counterpartyId = :memberId)) "
            + "and m.senderId <> :memberId and m.read = false")
    long countUnreadForMember(@Param("memberId") int memberId);
}
