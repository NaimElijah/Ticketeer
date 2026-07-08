package com.ticketing.system.messaging.adapter.out.persistence;
import com.ticketing.system.messaging.application.service.MessagingService;

import com.ticketing.system.Infrastructure.persistence.RepositoryLocks;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import com.ticketing.system.messaging.domain.Conversation;
import com.ticketing.system.messaging.domain.ConversationStatus;
import com.ticketing.system.messaging.domain.ConversationType;
import com.ticketing.system.messaging.application.port.out.ConversationRepository;
import com.ticketing.system.messaging.domain.ParticipantType;

/**
 * In-memory {@link ConversationRepository}. Lets Spring wire MessagingService.
 * {@code @Profile("!jpa")}: the {@code jpa} run/dev profile swaps in
 * {@link JpaConversationRepository} instead.
 *
 * <p>Read-tracking ({@code countUnreadForMember}) is computed by summing
 * {@link Conversation#unreadCountFor(int)} over the conversations where the
 * member is a participant.
 */
@Repository
@Profile("!jpa")
public class MemoryConversationRepository implements ConversationRepository {

    private final Map<String, Conversation> conversationsById = new ConcurrentHashMap<>();
    private final RepositoryLocks<String> locks = new RepositoryLocks<>();

    @Override
    public void lockForUpdate(String id) { locks.lock(id); }

    @Override
    public void unlock(String id) { locks.unlock(id); }


    @Override
    public void save(Conversation conversation) {
        conversationsById.put(conversation.getConversationId(), conversation);
    }

    @Override
    public Optional<Conversation> findById(String conversationId) {
        if (conversationId == null) return Optional.empty();
        return Optional.ofNullable(conversationsById.get(conversationId));
    }

    @Override
    public List<Conversation> findByParticipant(int participantId, ParticipantType participantType) {
        List<Conversation> result = new ArrayList<>();
        for (Conversation c : conversationsById.values()) {
            boolean asInitiator = c.getInitiatorId() == participantId
                    && c.getInitiatorType() == participantType;
            boolean asCounterparty = c.getCounterpartyId() == participantId
                    && c.getCounterpartyType() == participantType;
            if (asInitiator || asCounterparty) result.add(c);
        }
        return result;
    }

    @Override
    public List<Conversation> findMemberInbox(int memberId) {
        List<Conversation> result = new ArrayList<>();
        for (Conversation c : conversationsById.values()) {
            boolean inquiryByMember = c.getType() == ConversationType.INQUIRY
                    && c.getInitiatorType() == ParticipantType.MEMBER
                    && c.getInitiatorId() == memberId;
            boolean complaintByMember = c.getType() == ConversationType.COMPLAINT
                    && c.getInitiatorType() == ParticipantType.MEMBER
                    && c.getInitiatorId() == memberId;
            boolean directToMember = c.getType() == ConversationType.DIRECT
                    && c.getCounterpartyType() == ParticipantType.MEMBER
                    && c.getCounterpartyId() == memberId;
            if (inquiryByMember || complaintByMember || directToMember) {
                result.add(c);
            }
        }
        return result;
    }

    @Override
    public List<Conversation> findByType(ConversationType type) {
        List<Conversation> result = new ArrayList<>();
        for (Conversation c : conversationsById.values()) {
            if (c.getType() == type) result.add(c);
        }
        return result;
    }

    @Override
    public List<Conversation> findByTypeAndInitiatorType(ConversationType type, ParticipantType initiatorType) {
        List<Conversation> result = new ArrayList<>();
        for (Conversation c : conversationsById.values()) {
            if (c.getType() == type && c.getInitiatorType() == initiatorType) result.add(c);
        }
        return result;
    }

    @Override
    public List<Conversation> findByCompanyAsCounterparty(int companyId) {
        List<Conversation> result = new ArrayList<>();
        for (Conversation c : conversationsById.values()) {
            if (c.getCounterpartyType() == ParticipantType.COMPANY
                    && c.getCounterpartyId() == companyId) {
                result.add(c);
            }
        }
        return result;
    }

    @Override
    public int countUnreadForMember(int memberId) {
        int total = 0;
        for (Conversation c : conversationsById.values()) {
            boolean isParticipant = (c.getInitiatorType() == ParticipantType.MEMBER
                            && c.getInitiatorId() == memberId)
                    || (c.getCounterpartyType() == ParticipantType.MEMBER
                            && c.getCounterpartyId() == memberId);
            if (isParticipant) {
                total += c.unreadCountFor(memberId);
            }
        }
        return total;
    }

    @Override
    public List<Conversation> findByTypeAndStatus(ConversationType type, ConversationStatus status) {
        List<Conversation> result = new ArrayList<>();
        for (Conversation c : conversationsById.values()) {
            if (c.getType() == type && c.getStatus() == status) result.add(c);
        }
        return result;
    }
}
