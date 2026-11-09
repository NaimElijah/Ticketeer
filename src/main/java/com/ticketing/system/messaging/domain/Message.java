package com.ticketing.system.messaging.domain;

import java.time.LocalDateTime;

import com.ticketing.system.shared.InvariantChecked;
import com.ticketing.system.shared.domain.TextLimits;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One message of a {@link Conversation}, an owned child entity with no repository of its own.
 * {@code senderId} refers to a user, a production company or an admin, according to
 * {@code senderType}.
 *
 * <p>{@code messageId} is an assigned UUID and {@code senderType} is stored by name. The read flag is
 * the one mutable field; it maps to {@code is_read} because {@code READ} is a SQL reserved word. The
 * fields are not final and a protected no-argument constructor exists so Hibernate can hydrate it; the
 * public constructor still enforces the invariants.
 */
@Entity
@Table(name = "messages")
public class Message implements InvariantChecked {

    @Id
    private String messageId;

    @Column(name = "sender_id", nullable = false)
    private int senderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "sender_type", nullable = false)
    private ParticipantType senderType;

    @Column(nullable = false)
    private String body;

    @Column(name = "sent_at", nullable = false)
    private LocalDateTime sentAt;

    @Column(name = "is_read", nullable = false)
    private boolean read;

    /** For JPA only — do not call from application code. */
    protected Message() { }

    /**
     * Creates an unread message.
     *
     * @param messageId the message's id: not blank
     * @param senderId the sender's id, read together with {@code senderType}
     * @param senderType the side that sends it: not null
     * @param body the text: not blank, at most {@link TextLimits#MAX_TEXT_LENGTH} characters
     * @param sentAt when it was sent: not null
     * @throws com.ticketing.system.shared.exception.BusinessRuleViolationException if the body is too
     *         long
     * @throws IllegalStateException if the id or the body is null or blank, or the sender type or the
     *         time is null
     */
    public Message(String messageId, int senderId, ParticipantType senderType,
                   String body, LocalDateTime sentAt) {
        TextLimits.requireFits("Message", body);
        this.messageId = messageId;
        this.senderId = senderId;
        this.senderType = senderType;
        this.body = body;
        this.sentAt = sentAt;
        this.read = false;
        checkInvariants();
    }

    @Override
    public void checkInvariants() {
        if (messageId == null || messageId.isBlank()) {
            throw new IllegalStateException("Message invariant violated: messageId must be non-blank");
        }
        if (senderType == null) {
            throw new IllegalStateException("Message invariant violated: senderType must not be null");
        }
        if (body == null || body.isBlank()) {
            throw new IllegalStateException("Message invariant violated: body must be non-blank");
        }
        if (sentAt == null) {
            throw new IllegalStateException("Message invariant violated: sentAt must not be null");
        }
    }

    public String getMessageId() { return messageId; }

    public int getSenderId() { return senderId; }

    public ParticipantType getSenderType() { return senderType; }

    public String getBody() { return body; }

    public LocalDateTime getSentAt() { return sentAt; }

    public boolean isRead() { return read; }

    // Flips false -> true. Idempotent. Package-private: only Conversation.markReadBy decides which
    // messages a reader has read (#187).
    void markRead() {
        this.read = true;
    }
}
