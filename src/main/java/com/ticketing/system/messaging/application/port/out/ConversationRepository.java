package com.ticketing.system.messaging.application.port.out;
import com.ticketing.system.messaging.domain.ParticipantType;
import com.ticketing.system.messaging.domain.ConversationType;
import com.ticketing.system.messaging.domain.ConversationStatus;
import com.ticketing.system.messaging.domain.Conversation;

import java.util.List;
import java.util.Optional;

import com.ticketing.system.shared.IRepository;

/**
 * Driven port for the {@link Conversation} aggregate, with its messages. The one repository of the
 * messaging subsystem: inquiries, complaints and admin outreach are all conversations.
 */
public interface ConversationRepository extends IRepository<Conversation, String> {

    void save(Conversation conversation);

    Optional<Conversation> findById(String conversationId);

    /**
     * Returns the conversations in which the participant is the initiator or the counterparty, matching
     * both id and type. The admin inbox reads it with {@link ParticipantType#ADMIN}.
     */
    List<Conversation> findByParticipant(int participantId, ParticipantType participantType);

    // Member Support Inbox: INQUIRY / COMPLAINT the member initiated, plus DIRECT admin outreach
    // where the member is the counterparty. Excludes inquiries TO a company the member owns
    // (those are company-counterparty threads and belong in the company "Customer Inquiries" view).
    List<Conversation> findMemberInbox(int memberId);

    // Admin-side typed queries (II.6.3.1 — find all complaints).
    List<Conversation> findByType(ConversationType type);

    // Admin outreach history (II.6.3.2) — DIRECT conversations the admin initiated.
    List<Conversation> findByTypeAndInitiatorType(ConversationType type, ParticipantType initiatorType);

    // Company-side support inbox (II.4.4) — conversations where the company is counterparty.
    List<Conversation> findByCompanyAsCounterparty(int companyId);

    /**
     * Returns the conversations of the given type that have the given status. The admin complaint queue
     * reads it for one status; its date filters are applied by the service, not here.
     */
    List<Conversation> findByTypeAndStatus(ConversationType type, ConversationStatus status);
}
