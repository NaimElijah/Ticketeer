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
 *
 * <p>Every list finder returns an empty list when nothing matches, in no defined order.
 */
public interface ConversationRepository extends IRepository<Conversation, String> {

    /** Stores the conversation with its messages: a new one is inserted, a stored one updated. */
    void save(Conversation conversation);

    /**
     * Returns the conversation with that id.
     *
     * @param conversationId the id; {@code null} matches nothing
     * @return the conversation, or empty if there is none
     */
    Optional<Conversation> findById(String conversationId);

    /**
     * Returns the conversations in which the participant is the initiator or the counterparty, matching
     * both id and type. The admin inbox reads it with {@link ParticipantType#ADMIN}.
     */
    List<Conversation> findByParticipant(int participantId, ParticipantType participantType);

    /**
     * Returns the member's Support Inbox: the INQUIRY and COMPLAINT conversations the member initiated,
     * plus the DIRECT admin outreach where the member is the counterparty. It leaves out inquiries other
     * members sent to a company the member owns; those are company-counterparty threads and belong in
     * the company inbox.
     */
    List<Conversation> findMemberInbox(int memberId);

    /** Returns the conversations of one type, as for the admin complaint queue (II.6.3.1). */
    List<Conversation> findByType(ConversationType type);

    /**
     * Returns the conversations of one type that were opened by the given kind of party, as for the
     * admin outreach history (II.6.3.2): DIRECT conversations initiated by {@code ADMIN}. It does not
     * narrow to one admin.
     */
    List<Conversation> findByTypeAndInitiatorType(ConversationType type, ParticipantType initiatorType);

    /** Returns the conversations where the company is the counterparty, as for its inbox (II.4.4). */
    List<Conversation> findByCompanyAsCounterparty(int companyId);

    /**
     * Returns the conversations of the given type that have the given status. The admin complaint queue
     * reads it for one status; its date filters are applied by the service, not here.
     */
    List<Conversation> findByTypeAndStatus(ConversationType type, ConversationStatus status);
}
