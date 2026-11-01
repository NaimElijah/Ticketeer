package com.ticketing.system.messaging.domain;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.ticketing.system.shared.exception.BusinessRuleViolationException;
import com.ticketing.system.shared.exception.ConversationClosedException;
import com.ticketing.system.shared.exception.InvalidParticipantException;
import com.ticketing.system.shared.exception.InvalidStateTransitionException;
import com.ticketing.system.shared.InvariantChecked;
import com.ticketing.system.shared.domain.TextLimits;

import org.hibernate.annotations.Fetch;
import org.hibernate.annotations.FetchMode;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

// Aggregate root for the centralized messaging subsystem.
// Replaces the per-User MessageInbox, per-Company Inbox, and the standalone Complaint aggregate.
//
// Two-party model: an initiator and a counterparty. Either side can be a specific entity
// (MEMBER/COMPANY/ADMIN) or a group descriptor (ADMIN_GROUP / SYSTEM).
//
// Cross-aggregate references by ID per course rules — never holds direct refs to User / ProductionCompany / Admin.
// V3: mapped to JPA. conversationId is an ASSIGNED @Id (a UUID); type/status and the two participant
// types are stored by name. The polymorphic participants stay as plain (id, type) by-id column pairs
// — never @ManyToOne. @Version drives optimistic locking. messages are owned children
// (@OneToMany cascade-all + orphan-removal) kept in send order via @OrderColumn, loaded eagerly so a
// detached Conversation is fully usable. A protected no-arg ctor lets Hibernate hydrate; the public
// ctor still enforces the invariants and the message shape.
@Entity
@Table(name = "conversations")
public class Conversation implements InvariantChecked {

    @Id
    private String conversationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ConversationType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ConversationStatus status;

    // Initiator (the party that started the thread).
    @Column(name = "initiator_id", nullable = false)
    private int initiatorId;
    @Enumerated(EnumType.STRING)
    @Column(name = "initiator_type", nullable = false)
    private ParticipantType initiatorType;

    // Counterparty (the recipient — may be a specific entity or a group descriptor).
    @Column(name = "counterparty_id", nullable = false)
    private int counterpartyId;        // 0 / sentinel when counterpartyType is a group
    @Enumerated(EnumType.STRING)
    @Column(name = "counterparty_type", nullable = false)
    private ParticipantType counterpartyType;

    @Column
    private String subject;
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
    @Column(name = "last_message_at", nullable = false)
    private LocalDateTime lastMessageAt;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "conversation_id")
    @OrderColumn(name = "message_order")
    @Fetch(FetchMode.SUBSELECT)
    private List<Message> messages;

    @Version
    private Long version;

    /** For JPA only — do not call from application code. */
    protected Conversation() { }

    /**
     * Builds an OPEN conversation from its parts, and validates them: application code opens one with
     * {@link #start} instead. The conversation keeps its own copy of {@code messages}, so later changes
     * to the caller's list do not reach it.
     *
     * <p>The messages must form a thread that {@link #start} and {@link #addMessage} could have built,
     * one message at a time: at least one message, none null, the first from the initiator, and each
     * later one from a sender {@code addMessage} would accept (a participant; on a complaint, a single
     * admin reply and nothing from the member). Each message has the effect {@code addMessage} gives
     * it, so the status and {@link #getLastMessageAt} follow the thread: a counterparty reply makes it
     * RESPONDED, an initiator reply reopens it.
     *
     * @throws IllegalArgumentException if {@code messages} breaks that shape
     * @throws IllegalStateException if a field breaks an invariant ({@link #checkInvariants}), such as
     *         a message sent before {@code createdAt}
     * @throws com.ticketing.system.shared.exception.BusinessRuleViolationException if the subject is
     *         longer than {@link TextLimits#MAX_TEXT_LENGTH}
     */
    public Conversation(String conversationId, ConversationType type,
                        int initiatorId, ParticipantType initiatorType,
                        int counterpartyId, ParticipantType counterpartyType,
                        String subject, LocalDateTime createdAt,
                        List<Message> messages) {
        TextLimits.requireFits("Subject", subject);
        this.conversationId = conversationId;
        this.type = type;
        this.status = ConversationStatus.OPEN;
        this.initiatorId = initiatorId;
        this.initiatorType = initiatorType;
        this.counterpartyId = counterpartyId;
        this.counterpartyType = counterpartyType;
        this.subject = subject;
        this.createdAt = createdAt;
        this.lastMessageAt = createdAt;
        this.messages = new ArrayList<>();
        checkInvariants();
        appendInSendOrder(messages);
        checkInvariants();
    }

    // Factory — opens a new conversation with its first message already attached.
    // Generates the conversationId, the first messageId, and the timestamps internally
    // so id/time creation stays in the domain (callers don't synthesize them).
    public static Conversation start(ConversationType type,
                                     int initiatorId, ParticipantType initiatorType,
                                     int counterpartyId, ParticipantType counterpartyType,
                                     String subject, String firstMessageBody, LocalDateTime now) {
        Message first = new Message(UUID.randomUUID().toString(),
                initiatorId, initiatorType, firstMessageBody, now);
        List<Message> messages = new ArrayList<>();
        messages.add(first);
        return new Conversation(UUID.randomUUID().toString(), type,
                initiatorId, initiatorType, counterpartyId, counterpartyType,
                subject, now, messages);
    }

    public String getConversationId() { return conversationId; }
    public ConversationType getType() { return type; }
    public ConversationStatus getStatus() { return status; }
    public int getInitiatorId() { return initiatorId; }
    public ParticipantType getInitiatorType() { return initiatorType; }
    public int getCounterpartyId() { return counterpartyId; }
    public ParticipantType getCounterpartyType() { return counterpartyType; }
    public String getSubject() { return subject; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getLastMessageAt() { return lastMessageAt; }
    public List<Message> getMessages() { return List.copyOf(messages); }

    // Append a new message. Updates lastMessageAt and may transition status (OPEN→RESPONDED, etc).
    // Throws ConversationClosedException if status is CLOSED or RESOLVED.
    // Throws InvalidParticipantException if sender is not initiator or counterparty.
    public void addMessage(int senderId, ParticipantType senderType, String body, LocalDateTime now) {
        if (isClosed()) {
            throw new ConversationClosedException(conversationId);
        }
        requireMayReply(senderId, senderType);
        append(new Message(UUID.randomUUID().toString(), senderId, senderType, body, now));
        checkInvariants();
    }

    // Adds an accepted message and applies its effect. The one path for addMessage and the public
    // constructor, so a rebuilt thread reads as the one addMessage built.
    private void append(Message message) {
        messages.add(message);
        this.lastMessageAt = message.getSentAt();
        // Auto status flip between the two sides: the initiator re-opens; the other side responds.
        if (isInitiatorSide(message.getSenderId(), message.getSenderType())) {
            if (status == ConversationStatus.RESPONDED) {
                status = ConversationStatus.OPEN;
            }
        } else if (status == ConversationStatus.OPEN) {
            status = ConversationStatus.RESPONDED;
        }
    }

    // The rules addMessage applies to every message after the first, against the messages so far.
    private void requireMayReply(int senderId, ParticipantType senderType) {
        // One-shot complaint: a COMPLAINT carries the member's single opening message and accepts
        // exactly one admin reply (which resolves it). The member can never reply, and a second
        // admin reply is rejected — enforced here so no service path can bypass it.
        if (type == ConversationType.COMPLAINT) {
            if (senderType != ParticipantType.ADMIN) {
                throw new BusinessRuleViolationException(
                        "Complaints accept only a single admin response; the member cannot reply");
            }
            boolean alreadyAnswered = messages.stream()
                    .anyMatch(m -> m.getSenderType() == ParticipantType.ADMIN);
            if (alreadyAnswered) {
                throw new BusinessRuleViolationException(
                        "Complaint " + conversationId + " has already received its admin response");
            }
        }
        if (!involvesParticipant(senderId, senderType)) {
            throw new InvalidParticipantException(senderId, conversationId);
        }
    }

    // Adds the given messages one at a time under the rules start and addMessage enforce, and with the
    // effect addMessage gives each, so the public constructor builds only a thread they could have
    // built, in the state they would have left it (#187).
    private void appendInSendOrder(List<Message> given) {
        if (given == null || given.isEmpty()) {
            throw new IllegalArgumentException("A conversation opens with its first message");
        }
        for (Message message : given) {
            if (message == null) {
                throw new IllegalArgumentException("A conversation's messages must not be null");
            }
            if (messages.isEmpty()) {
                if (message.getSenderId() != initiatorId || message.getSenderType() != initiatorType) {
                    throw new IllegalArgumentException("The initiator writes a conversation's first message");
                }
            } else {
                try {
                    requireMayReply(message.getSenderId(), message.getSenderType());
                } catch (BusinessRuleViolationException | InvalidParticipantException e) {
                    throw new IllegalArgumentException(
                            "Message " + message.getMessageId() + " could not have been added: " + e.getMessage(), e);
                }
            }
            append(message);
        }
    }

    /**
     * Marks read every message the reader's side did not send, as when the reader opens the thread.
     * Idempotent, and valid on a closed thread, which stays readable.
     *
     * @param readerId the reader's id, read together with {@code readerType}
     * @param readerType the side the reader acts as; for {@link ParticipantType#ADMIN}, a message from
     *        any admin is the reader's own (see {@link #unreadCountFor})
     * @throws InvalidParticipantException if the reader is not a participant of this conversation
     */
    public void markReadBy(int readerId, ParticipantType readerType) {
        if (!involvesParticipant(readerId, readerType)) {
            throw new InvalidParticipantException(readerId, conversationId);
        }
        for (Message m : messages) {
            if (!fromReadersSide(m, readerId, readerType)) {
                m.markRead();
            }
        }
    }

    // Status transitions.
    public void transitionToResponded() {
        if (isClosed()) {
            throw new InvalidStateTransitionException("Conversation", status.name(),
                    ConversationStatus.RESPONDED.name());
        }
        this.status = ConversationStatus.RESPONDED;
        checkInvariants();
    }

    public void transitionToResolved() {
        if (status == ConversationStatus.RESOLVED) {
            return; // idempotent
        }
        if (status == ConversationStatus.CLOSED) {
            throw new InvalidStateTransitionException("Conversation", status.name(),
                    ConversationStatus.RESOLVED.name());
        }
        this.status = ConversationStatus.RESOLVED;
        checkInvariants();
    }

    public void transitionToClosed() {
        if (status == ConversationStatus.CLOSED) {
            return; // idempotent
        }
        this.status = ConversationStatus.CLOSED;
        checkInvariants();
    }

    // State checks.
    public boolean isClosed() {
        return status == ConversationStatus.CLOSED || status == ConversationStatus.RESOLVED;
    }

    public boolean involvesParticipant(int participantId, ParticipantType participantType) {
        if (participantType == null) {
            return false;
        }
        boolean matchesInitiator = participantId == initiatorId && participantType == initiatorType;
        boolean matchesCounterparty = participantId == counterpartyId && participantType == counterpartyType;
        if (matchesInitiator || matchesCounterparty) {
            return true;
        }
        // A specific ADMIN acts on behalf of the ADMIN_GROUP (the complaint queue).
        if (participantType == ParticipantType.ADMIN) {
            return initiatorType == ParticipantType.ADMIN_GROUP
                    || counterpartyType == ParticipantType.ADMIN_GROUP;
        }
        return false;
    }

    /**
     * Counts the messages from the other side that are not yet read.
     *
     * <p>Admins answer the complaint queue as one team, so for an {@link ParticipantType#ADMIN} reader
     * a message from any admin is the reader's own: admin B never counts admin A's reply as unread.
     *
     * @param readerId the reader's id, read together with {@code readerType}
     * @param readerType the side the reader acts as
     * @return the number of unread messages the reader's side did not send
     */
    public int unreadCountFor(int readerId, ParticipantType readerType) {
        int count = 0;
        for (Message m : messages) {
            if (!fromReadersSide(m, readerId, readerType) && !m.isRead()) {
                count++;
            }
        }
        return count;
    }

    // Member, company and admin ids come from separate sequences that each start at 1, so an id alone
    // does not tell whose a message is: a company often shares its id with the member it writes to.
    // Admins are the exception: they act as one side, whichever admin wrote.
    private static boolean fromReadersSide(Message message, int readerId, ParticipantType readerType) {
        if (readerType == ParticipantType.ADMIN && message.getSenderType() == ParticipantType.ADMIN) {
            return true;
        }
        return message.getSenderId() == readerId && message.getSenderType() == readerType;
    }

    // Whether the given sender represents the initiator side of this thread
    // (a specific ADMIN counts as the initiator when the initiator is the ADMIN_GROUP).
    private boolean isInitiatorSide(int senderId, ParticipantType senderType) {
        if (senderId == initiatorId && senderType == initiatorType) {
            return true;
        }
        return senderType == ParticipantType.ADMIN && initiatorType == ParticipantType.ADMIN_GROUP;
    }

    @Override
    public void checkInvariants() {
        if (conversationId == null || conversationId.isBlank()) {
            throw new IllegalStateException("Conversation invariant violated: conversationId must be non-blank");
        }
        if (type == null) {
            throw new IllegalStateException("Conversation invariant violated: type must not be null");
        }
        if (status == null) {
            throw new IllegalStateException("Conversation invariant violated: status must not be null");
        }
        if (initiatorType == null) {
            throw new IllegalStateException("Conversation invariant violated: initiatorType must not be null");
        }
        if (counterpartyType == null) {
            throw new IllegalStateException("Conversation invariant violated: counterpartyType must not be null");
        }
        if (createdAt == null) {
            throw new IllegalStateException("Conversation invariant violated: createdAt must not be null");
        }
        if (lastMessageAt == null) {
            throw new IllegalStateException("Conversation invariant violated: lastMessageAt must not be null");
        }
        if (lastMessageAt.isBefore(createdAt)) {
            throw new IllegalStateException("Conversation invariant violated: lastMessageAt must be >= createdAt");
        }
        if (messages == null) {
            throw new IllegalStateException("Conversation invariant violated: messages list must not be null");
        }
    }
}
