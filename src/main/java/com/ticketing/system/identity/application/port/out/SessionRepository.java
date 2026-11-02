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
     * for {@code userId}; Guest and Admin rows never count, since an admin's id can
     * equal a member's (ADR-012). Used by {@code SessionManager.isOnline} (UC-35/36).
     *
     * <p>With multi-device login (Q4), several sessions may share a userId;
     * this method only answers presence, not multiplicity.
     */
    boolean existsByUserId(int userId);

    /** Idempotent — no-op when the session is unknown. */
    void delete(String sessionId);

    /**
     * Deletes every {@link com.ticketing.system.identity.domain.SessionKind#ADMIN ADMIN} session of
     * {@code adminId}, expired or not, so no token issued for them validates any more. A Member row
     * with the same numeric id belongs to a different principal and stays (ADR-012), as do other
     * admins' rows and guest rows. A no-op when the admin has no session.
     *
     * @param adminId the admin whose sessions end
     */
    void deleteAdminSessions(int adminId);

    /**
     * Sweep query for UC-2. Returns every session whose {@code expiresAt}
     * is at or before {@code cutoff}.
     */
    List<Session> findExpiredBefore(Instant cutoff);
}
