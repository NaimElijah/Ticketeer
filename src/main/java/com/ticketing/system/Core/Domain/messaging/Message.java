package com.ticketing.system.Core.Domain.messaging;

import java.time.LocalDateTime;

import com.ticketing.system.shared.InvariantChecked;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

// Sub-entity of the Conversation aggregate.
// Identity is local to the conversation. Cross-aggregate refs by ID:
//   senderId — User / ProductionCompany / Admin (resolved by senderType)
//
// V3: an owned child @Entity of the Conversation aggregate (mapped via Conversation's @OneToMany,
// never its own repository). messageId is an ASSIGNED @Id (a UUID); senderType is stored by name.
// The mutable read flag maps to is_read (READ is a SQL reserved word). Fields are non-final with a
// protected no-arg ctor so Hibernate can hydrate; the public ctor still enforces the invariants.
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

    public Message(String messageId, int senderId, ParticipantType senderType,
                   String body, LocalDateTime sentAt) {
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

    // UI action — flips false -> true. Idempotent.
    public void markRead() {
        this.read = true;
    }
}
