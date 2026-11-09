package com.ticketing.system.notifications.domain;

import com.ticketing.system.shared.dto.NotificationDTO;
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

/**
 * One alert for one member (UC-35, UC-36, UC-37): its text, its type and where it is in its
 * {@link NotificationStatus} lifecycle. Aggregate root of the notifications context, kept apart from the
 * {@code User} aggregate so that high-volume PENDING storage and login-time delivery don't load it.
 *
 * <p>The lifecycle moves PENDING to SENT ({@link #markSent()}), SENT back to PENDING
 * ({@link #markPending()}), and SENT or PENDING to READ ({@link #markRead()}). Every transition and
 * every constructor re-checks {@link #checkInvariants()}.
 *
 * <p>Mapping: the String id is assigned by the application (the services use a random UUID) and never
 * generated. The recipient is referenced by its user id, a plain column and not an association.
 * {@code type} and {@code status} are stored by name, {@code @Version} drives optimistic locking, and the
 * {@code data} payload is one JSON text column via {@link NotificationDataJsonConverter} rather than a
 * side table. The fields are non-final with a protected no-arg constructor so Hibernate can hydrate; the
 * public constructors still enforce the invariants.
 */
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

    /** The width of {@code notifications.message} (V1); a longer message is cut to it. */
    public static final int MAX_MESSAGE_LENGTH = 255;
    private static final String ELLIPSIS = "…";

    @Column(length = MAX_MESSAGE_LENGTH)
    private String message;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
    /**
     * Structured payload — type-specific key/value data, the machine-readable companion of the
     * human-readable {@link #message}. Which keys a type carries is decided where the notification is
     * built ({@code NotificationService}); some types, such as PURCHASE_CONFIRMED, carry none. Not part
     * of {@link #toDTO()}. Never null — empty map for notifications with no extra context. Persisted as
     * one JSON text column (see {@link NotificationDataJsonConverter}).
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
     * Creates a notification with an empty {@link #data} payload.
     *
     * @param id              the notification's id; non-blank
     * @param recipientUserId the member it is for; positive
     * @param type            what the notification is about; not null
     * @param status          its starting status; not null
     * @param message         the text the member reads; null is accepted, and a text longer than
     *                        {@link #MAX_MESSAGE_LENGTH} is cut to it and ends in an ellipsis
     * @param createdAt       when it was raised, as UTC wall time (ADR-008); not null
     * @throws IllegalStateException if an invariant fails (see {@link #checkInvariants()})
     */
    public Notification(String id, int recipientUserId, NotificationType type,
            NotificationStatus status, String message, LocalDateTime createdAt) {
        this(id, recipientUserId, type, status, message, createdAt, new HashMap<>());
    }

    /**
     * Creates a notification with a structured {@code data} payload. The arguments are as for the
     * constructor without a payload.
     *
     * @param data the payload; copied, and null reads as empty
     * @throws IllegalStateException if an invariant fails (see {@link #checkInvariants()})
     */
    public Notification(String id, int recipientUserId, NotificationType type,
            NotificationStatus status, String message, LocalDateTime createdAt,
            Map<String, Object> data) {
        this.id = id;
        this.recipientUserId = recipientUserId;
        this.type = type;
        this.status = status;
        this.message = fitToColumn(message);
        this.createdAt = createdAt;
        this.data = data == null ? new HashMap<>() : new HashMap<>(data);
        checkInvariants();
    }

    // The message is composed from names and subjects nobody bounds. Cutting it keeps the notice: a longer
    // one would fail the insert on the async notify- thread, where the failure is only logged (ADR-004).
    // The width is in UTF-16 units, which H2 counts (PostgreSQL counts characters, so it fits there too),
    // and the cut never splits a surrogate pair.
    private static String fitToColumn(String message) {
        if (message == null || message.length() <= MAX_MESSAGE_LENGTH) {
            return message;
        }
        int end = MAX_MESSAGE_LENGTH - ELLIPSIS.length();
        if (Character.isHighSurrogate(message.charAt(end - 1))) {
            end--;
        }
        return message.substring(0, end) + ELLIPSIS;
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

    /**
     * The text the member reads, at most {@link #MAX_MESSAGE_LENGTH} characters; null if the
     * notification was created without one.
     */
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

    /**
     * Moves the notification from PENDING to SENT, once it has been pushed to the member's bell.
     *
     * @throws IllegalStateException if it is not PENDING; the status is unchanged
     */
    public void markSent() {
        if (status != NotificationStatus.PENDING) {
            throw new IllegalStateException(
                    "Cannot mark as SENT a notification that is not PENDING. Current status: " + status);
        }
        this.status = NotificationStatus.SENT;
        checkInvariants();
    }

    /**
     * Moves the notification from SENT back to PENDING, so that a later delivery shows it again. Used
     * when a member's session closes before they read it.
     *
     * @throws IllegalStateException if it is not SENT; the status is unchanged
     */
    public void markPending() {
        if (status != NotificationStatus.SENT) {
            throw new IllegalStateException(
                    "Cannot mark as PENDING a notification that is not SENT. Current status: " + status);
        }
        this.status = NotificationStatus.PENDING;
        checkInvariants();
    }

    /**
     * Moves the notification from SENT or PENDING to READ, when the member clicks it. PENDING is allowed
     * because a session ending in another browser or on another device sends SENT rows back to PENDING
     * while this session's bell still shows them, and that click must still count (#187).
     *
     * @throws IllegalStateException if it is already READ; the status is unchanged
     */
    public void markRead() {
        if (status != NotificationStatus.SENT && status != NotificationStatus.PENDING) {
            throw new IllegalStateException(
                    "Cannot mark as READ a notification that is not SENT or PENDING. Current status: " + status);
        }
        this.status = NotificationStatus.READ;
        checkInvariants();
    }

    public boolean isPending() {
        return status == NotificationStatus.PENDING;
    }

    public boolean isSent() {
        return status == NotificationStatus.SENT;
    }

    public boolean isRead() {
        return status == NotificationStatus.READ;
    }

    /**
     * Returns the view of this notification that the member sees: its id, type and status by name, the
     * message and the creation time. The recipient and the {@link #getData() data} payload are not part
     * of it.
     */
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
