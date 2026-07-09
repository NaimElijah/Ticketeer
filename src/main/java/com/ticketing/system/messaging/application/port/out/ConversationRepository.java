package com.ticketing.system.messaging.application.port.out;
import com.ticketing.system.messaging.domain.ParticipantType;
import com.ticketing.system.messaging.domain.ConversationType;
import com.ticketing.system.messaging.domain.ConversationStatus;
import com.ticketing.system.messaging.domain.Conversation;

import java.util.List;
import java.util.Optional;

import com.ticketing.system.shared.IRepository;

// Aggregate-root entry point for the Conversation aggregate (per course's
// IXxxRepository convention). Owns the centralized messaging subsystem —
// replaces per-User MessageInbox, per-Company Inbox, and the standalone Complaint repo.
public interface ConversationRepository extends IRepository<Conversation, String> {

    void save(Conversation conversation);

    Optional<Conversation> findById(String conversationId);

    // "My conversations" view — used by company support inbox.
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

    // Member inbox unread badge.
    int countUnreadForMember(int memberId);

    // Admin filter for complaint queues — by status and date.
    List<Conversation> findByTypeAndStatus(ConversationType type, ConversationStatus status);
}
