package com.ticketing.system.messaging.adapter.out.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.ticketing.system.messaging.domain.Conversation;
import com.ticketing.system.messaging.domain.ConversationStatus;
import com.ticketing.system.messaging.domain.ConversationType;
import com.ticketing.system.messaging.application.port.out.ConversationRepository;
import com.ticketing.system.messaging.domain.ParticipantType;

/**
 * JPA-backed {@link ConversationRepository} — active only in the {@code jpa} run/dev profile. Adapts
 * the domain port onto Spring Data ({@link SpringDataConversationRepository}); the application layer
 * depends only on {@code ConversationRepository}, never on Spring Data. Owned, send-ordered messages
 * ({@code @OneToMany} + {@code @OrderColumn}) persist by cascade with the conversation.
 *
 * <p>{@code lockForUpdate}/{@code unlock} are no-ops (concurrency via {@code @Version}). {@code save}
 * delegates to {@code data.save} under {@code @Version} (a fresh conversation inserts with its first
 * message; a loaded one updates, cascading new messages and read-flag changes) and is
 * {@code @Transactional}. {@code countUnreadForMember} is computed by the database from message
 * read-state.
 */
@Repository
@Profile("jpa")
public class JpaConversationRepository implements ConversationRepository {

    private final SpringDataConversationRepository data;

    public JpaConversationRepository(SpringDataConversationRepository data) {
        this.data = data;
    }

    @Override
    public void lockForUpdate(String id) { /* no-op — @Version optimistic locking */ }

    @Override
    public void unlock(String id) { /* no-op */ }

    @Override
    @Transactional
    public void save(Conversation conversation) {
        data.save(conversation);
    }

    @Override
    public Optional<Conversation> findById(String conversationId) {
        if (conversationId == null) {
            return Optional.empty();
        }
        return data.findById(conversationId);
    }

    @Override
    public List<Conversation> findByParticipant(int participantId, ParticipantType participantType) {
        return data.findByParticipant(participantId, participantType);
    }

    @Override
    public List<Conversation> findMemberInbox(int memberId) {
        return data.findMemberInbox(memberId);
    }

    @Override
    public List<Conversation> findByType(ConversationType type) {
        return data.findByType(type);
    }

    @Override
    public List<Conversation> findByTypeAndInitiatorType(ConversationType type, ParticipantType initiatorType) {
        return data.findByTypeAndInitiatorType(type, initiatorType);
    }

    @Override
    public List<Conversation> findByCompanyAsCounterparty(int companyId) {
        return data.findByCounterpartyTypeAndCounterpartyId(ParticipantType.COMPANY, companyId);
    }

    @Override
    public int countUnreadForMember(int memberId) {
        return (int) data.countUnreadForMember(memberId);
    }

    @Override
    public List<Conversation> findByTypeAndStatus(ConversationType type, ConversationStatus status) {
        return data.findByTypeAndStatus(type, status);
    }
}
