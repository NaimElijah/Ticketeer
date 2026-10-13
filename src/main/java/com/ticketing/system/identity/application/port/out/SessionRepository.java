package com.ticketing.system.identity.application.port.out;
import com.ticketing.system.identity.domain.Session;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.ticketing.system.shared.IRepository;

/**
 * Aggregate-root entry point for the {@link Session} aggregate.
 *
 * <p>Implemented in Infrastructure (V1: in-memory, V2: JPA-backed).
 * See docs/auth-rework-plan.md.
 */
public interface SessionRepository extends IRepository<Session, String> {

    /** Persist a new or updated session. */
    void save(Session session);

    /** Look up by sessionId — the primary key. */
    Optional<Session> findById(String sessionId);

    /**
     * Returns {@code true} iff at least one non-expired Member session exists
     * for {@code userId}. Used by {@code SessionManager.isOnline} (UC-35/36).
     *
     * <p>With multi-device login (Q4), several sessions may share a userId;
     * this method only answers presence, not multiplicity.
     */
    boolean existsByUserId(int userId);

    /** Idempotent — no-op when the session is unknown. */
    void delete(String sessionId);

    /**
     * Sweep query for UC-2. Returns every session whose {@code expiresAt}
     * is at or before {@code cutoff}.
     */
    List<Session> findExpiredBefore(Instant cutoff);
}
