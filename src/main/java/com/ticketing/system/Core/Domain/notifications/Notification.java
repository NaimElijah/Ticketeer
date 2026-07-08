package com.ticketing.system.Core.Domain.notifications;
import com.ticketing.system.sales.application.service.CheckoutService;
import com.ticketing.system.identity.domain.User;

import com.ticketing.system.Core.Application.dto.NotificationDTO;
import com.ticketing.system.shared.InvariantChecked;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

// Aggregate root for the Notification aggregate (UC-35 / UC-36 / UC-37 design walkthrough).
// Promoted from a sub-entity of User to its own aggregate so high-volume PENDING storage
// and login-time delivery don't drag the User aggregate.
//
// Cross-aggregate references by ID per course rules:
//   recipientUserId — User aggregate
// V3: mapped to JPA. The String @Id is ASSIGNED by the application (a UUID), never
// @GeneratedValue; recipientUserId is a plain by-id column (never @ManyToOne); the two
// enums are stored by name (@Enumerated STRING); @Version drives optimistic locking. The
// data payload is stored as one JSON text column via NotificationDataJsonConverter rather
// than an @ElementCollection side table. Fields are non-final with a protected no-arg ctor
// so Hibernate can hydrate; the public ctors still enforce the invariants.
@Entity
@Table(name = "notifications")
public class Notification implements InvariantChecked {

    @Id
    private String id;

    @Column(name = "recipient_user_id", nullable = false)
    private int recipientUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NotificationType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NotificationStatus status;

    @Column
    private String message;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
    /**
     * Structured payload — type-specific key/value data the recipient (or UI) can
     * render. E.g. PURCHASE_CONFIRMED carries {@code orderId}, {@code total},
     * {@code eventName}; EVENT_CANCELLED carries {@code eventId}, {@code reason}.
     * The {@link #message} stays the human-readable summary; {@code data} is the
     * machine-readable companion. Never null — empty map for notifications with
     * no extra context. Persisted as one JSON text column (see
     * {@link NotificationDataJsonConverter}).
     */
    @Convert(converter = NotificationDataJsonConverter.class)
    @Column(name = "data", columnDefinition = "text", nullable = false)
    private Map<String, Object> data;

    @Version
    private Long version;

    /** For JPA only — do not call from application code. */
    protected Notification() {
    }

    /**
     * Backward-compatible 6-arg constructor — leaves {@link #data} as an empty map.
     * Existing callers that don't carry structured payload data keep working.
     */
    public Notification(String id, int recipientUserId, NotificationType type,
            NotificationStatus status, String message, LocalDateTime createdAt) {
        this(id, recipientUserId, type, status, message, createdAt, new HashMap<>());
    }

    /**
     * Full constructor including the structured {@code data} payload. New callers
     * (e.g. CheckoutService building a PURCHASE_CONFIRMED notification) should use
     * this and populate the relevant type-specific keys.
     */
    public Notification(String id, int recipientUserId, NotificationType type,
            NotificationStatus status, String message, LocalDateTime createdAt,
            Map<String, Object> data) {
        this.id = id;
        this.recipientUserId = recipientUserId;
        this.type = type;
        this.status = status;
        this.message = message;
        this.createdAt = createdAt;
        this.data = data == null ? new HashMap<>() : new HashMap<>(data);
        checkInvariants();
    }

    public String getId() {
        return id;
    }

    public int getRecipientUserId() {
        return recipientUserId;
    }

    public NotificationType getType() {
        return type;
    }

    public NotificationStatus getStatus() {
        return status;
    }

    public String getMessage() {
        return message;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    /**
     * Unmodifiable view of the structured payload. Never null — returns an empty
     * map for notifications that don't carry extra data.
     */
    public Map<String, Object> getData() {
        return Collections.unmodifiableMap(data);
    }

    // Lifecycle transition: PENDING → SENT (notification shown in the bell).
    public void markSent() {
        if (status != NotificationStatus.PENDING) {
            throw new IllegalStateException(
                    "Cannot mark as SENT a notification that is not PENDING. Current status: " + status);
        }
        this.status = NotificationStatus.SENT;
        checkInvariants();
    }

    // Lifecycle transition: SENT → PENDING (user disconnected before reading).
    public void markPending() {
        if (status != NotificationStatus.SENT) {
            throw new IllegalStateException(
                    "Cannot mark as PENDING a notification that is not SENT. Current status: " + status);
        }
        this.status = NotificationStatus.PENDING;
        checkInvariants();
    }

    // Lifecycle transition: SENT → READ (user clicked the notification item).
    public void markRead() {
        if (status != NotificationStatus.SENT) {
            throw new IllegalStateException(
                    "Cannot mark as READ a notification that is not SENT. Current status: " + status);
        }
        this.status = NotificationStatus.READ;
        checkInvariants();
    }

    // State checks.
    public boolean isPending() {
        return status == NotificationStatus.PENDING;
    }

    public boolean isSent() {
        return status == NotificationStatus.SENT;
    }

    public boolean isRead() {
        return status == NotificationStatus.READ;
    }

    public NotificationDTO toDTO() {
        return new NotificationDTO(
                id,
                type.name(),
                status.name(),
                message,
                createdAt);
    }

    @Override
    public void checkInvariants() {
        if (id == null || id.isBlank()) {
            throw new IllegalStateException("Notification invariant violated: id must be non-blank");
        }
        if (recipientUserId <= 0) {
            throw new IllegalStateException(
                    "Notification invariant violated: recipientUserId must be positive (was " + recipientUserId + ")");
        }
        if (type == null) {
            throw new IllegalStateException("Notification invariant violated: type must not be null");
        }
        if (status == null) {
            throw new IllegalStateException("Notification invariant violated: status must not be null");
        }
        if (createdAt == null) {
            throw new IllegalStateException("Notification invariant violated: createdAt must not be null");
        }
        if (data == null) {
            throw new IllegalStateException(
                    "Notification invariant violated: data map must not be null (use empty map for no payload)");
        }
    }
}
