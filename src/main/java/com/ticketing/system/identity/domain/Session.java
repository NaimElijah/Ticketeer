package com.ticketing.system.identity.domain;

import java.time.Instant;

import com.ticketing.system.shared.InvariantChecked;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * Session aggregate — the unified identity record for both Member and Guest
 * sessions. Part of the auth rework (see docs/auth-rework-plan.md).
 *
 * <ul>
 *   <li><b>Member session:</b> {@code userId != null}. Issued by
 *       {@code AuthenticationService.login}; the matching JWT carries
 *       {@code sid = sessionId}.</li>
 *   <li><b>Guest session:</b> {@code userId == null}. Started anonymously via
 *       {@code AuthenticationService.startGuestSession}; the raw sessionId is
 *       the credential (no JWT).</li>
 * </ul>
 *
 * <p>A Guest session can be promoted to a Member session in-place via
 * {@link #promoteTo(int, Instant)} — the sessionId is preserved, so any cart
 * or other state already attached to it survives the transition.
 *
 * <p>V3: mapped to JPA. The {@code sessionId} {@code @Id} is ASSIGNED by the
 * application (the JWT {@code sid} for members, a generated id for guests), never
 * {@code @GeneratedValue}. {@code userId} is a plain by-id column — never a
 * {@code @ManyToOne} to User (the cross-aggregate reference rule). {@code @Version}
 * drives optimistic locking and lets Spring Data tell a new entity (version == null)
 * from a loaded one. Fields are non-final and a protected no-arg constructor exists
 * purely so Hibernate can hydrate instances — the public constructor still enforces
 * the invariants for application-created sessions.
 */
@Entity
@Table(name = "sessions")
public class Session implements InvariantChecked {

    @Id
    private String sessionId;

    @Column(name = "user_id")
    private Integer userId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Version
    private Long version;

    /** For JPA only — do not call from application code. */
    protected Session() { }

    public Session(String sessionId, Integer userId, Instant createdAt, Instant expiresAt) {
        this.sessionId = sessionId;
        this.userId = userId;
        this.createdAt = createdAt;
        this.lastSeenAt = createdAt;
        this.expiresAt = expiresAt;
        checkInvariants();
    }

    public String getSessionId() {
        return sessionId;
    }

    /** {@code null} for Guest sessions. */
    public Integer getUserId() {
        return userId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    /**
     * The JPA optimistic-lock version: {@code null} for a freshly constructed session
     * (never persisted), non-null once loaded from the database. Lets the persistence
     * adapter tell a new instance from a loaded one.
     */
    public Long getVersion() {
        return version;
    }

    public boolean isMember() {
        return userId != null;
    }

    public boolean isGuest() {
        return userId == null;
    }

    /**
     * Promotes a Guest session to a Member session in-place. The sessionId is
     * preserved, so any cart attached to it survives.
     *
     * @throws IllegalStateException if this session is already a Member
     */
    public void promoteTo(int newUserId, Instant newExpiresAt) {
        if (this.userId != null) {
            throw new IllegalStateException("session already a member");
        }
        if (newExpiresAt == null) {
            throw new IllegalArgumentException("newExpiresAt must not be null");
        }
        this.userId = newUserId;
        this.expiresAt = newExpiresAt;
        checkInvariants();
    }

    /** Records activity. Used by the idle-timeout flow for Guest sessions. */
    public void touch(Instant now) {
        if (now == null) {
            throw new IllegalArgumentException("now must not be null");
        }
        // Validate-before-commit: roll back if `now` precedes createdAt, so a rejected
        // call never leaves lastSeenAt before createdAt.
        Instant previous = this.lastSeenAt;
        this.lastSeenAt = now;
        try {
            checkInvariants();
        } catch (RuntimeException ex) {
            this.lastSeenAt = previous;
            throw ex;
        }
    }

    /** Extends the expiry. Used by the idle-timeout flow when a Guest is active. */
    public void extendExpiry(Instant newExpiresAt) {
        if (newExpiresAt == null) {
            throw new IllegalArgumentException("newExpiresAt must not be null");
        }
        this.expiresAt = newExpiresAt;
        checkInvariants();
    }

    /**
     * Persistence support: copies the updatable state ({@code userId},
     * {@code lastSeenAt}, {@code expiresAt}) from {@code source} onto this session,
     * preserving identity ({@code sessionId}, {@code createdAt}) and the JPA
     * {@code @Version}. Used by the JPA adapter to reproduce the in-memory
     * repository's overwrite-on-save semantics when a brand-new instance reuses an
     * already-persisted id — a case the normal merge/persist path can't express for
     * an assigned id. Not part of the normal domain mutation flow (use
     * {@link #promoteTo}, {@link #touch}, {@link #extendExpiry}).
     */
    public void overwriteFrom(Session source) {
        this.userId = source.userId;
        this.lastSeenAt = source.lastSeenAt;
        this.expiresAt = source.expiresAt;
        checkInvariants();
    }

    /** Boundary is inclusive: {@code now == expiresAt} counts as expired. */
    public boolean isExpiredAt(Instant now) {
        if (now == null) {
            throw new IllegalArgumentException("now must not be null");
        }
        return !now.isBefore(expiresAt);
    }

    @Override
    public void checkInvariants() {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalStateException("Session invariant violated: sessionId must be non-blank");
        }
        if (createdAt == null) {
            throw new IllegalStateException("Session invariant violated: createdAt must not be null");
        }
        if (lastSeenAt == null) {
            throw new IllegalStateException("Session invariant violated: lastSeenAt must not be null");
        }
        if (expiresAt == null) {
            throw new IllegalStateException("Session invariant violated: expiresAt must not be null");
        }
        if (lastSeenAt.isBefore(createdAt)) {
            throw new IllegalStateException("Session invariant violated: lastSeenAt must be >= createdAt");
        }
        // Note: userId is intentionally NOT bounds-checked here. The Session domain stays
        // permissive about userId values — positive/zero/negative is the User aggregate's
        // concern. See SessionTest#memberSession_acceptsZeroAndNegativeUserIds.
    }
}
