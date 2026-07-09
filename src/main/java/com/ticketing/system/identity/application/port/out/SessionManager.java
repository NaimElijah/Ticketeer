package com.ticketing.system.identity.application.port.out;

import java.util.Optional;

import com.ticketing.system.identity.domain.Session;

/**
 * Port for session / token management. Implemented in Infrastructure by
 * {@code JwtSessionManager}. Used by {@code AuthenticationService} (UC-12/14)
 * and {@code NotificationDispatchService} (UC-35/36 — online check).
 */
public interface SessionManager {

    /** Issues a token after successful login. UC-12. */
    String generateToken(int userId, String username);

    /**
     * Issues a JWT bound to an existing Session row. Used by promote-on-login
     * (Phase 3 of the unified-session rework) so the Guest's sessionId is
     * preserved into the Member flow and any attached cart survives.
     *
     * @throws IllegalArgumentException if {@code session} is null
     * @throws IllegalStateException if {@code session.isGuest()}
     */
    String generateTokenForSession(Session session, String username);

    /**
     * Admin sign-in (#290): issue a JWT for an admin that carries the ADMIN role claim, so the
     * admin-authorization gate cannot be satisfied by a member token even when admin ids and
     * member ids share an id space.
     */
    String generateAdminToken(int adminId, String username);

    /** True iff the (valid) token carries the ADMIN role claim. Never throws. */
    boolean isAdminToken(String token);

    /**
     * Validates a token's signature and expiry. UC-12.
     *
     * @return {@code true} for a well-formed, signed, unexpired token; {@code false}
     *     only for {@code null} / blank input
     * @throws com.ticketing.system.shared.exception.InvalidTokenException
     *     malformed, bad signature, or revoked
     * @throws com.ticketing.system.shared.exception.SessionExpiredException
     *     past the {@code exp} claim
     */
    boolean validateToken(String token);

    /**
     * Validates a credential that may be either a JWT (Member) or a raw
     * sessionId (Guest). Returns {@code true} iff the credential corresponds
     * to a live, non-expired Session row.
     *
     * <p>Used by guest-friendly endpoints (UC-3 / UC-8 catalog browse) where
     * the caller may not have a JWT. Never throws — converts any internal
     * validation failure to {@code false}.
     */
    boolean validateCredential(String credential);

    /** Returns the user id encoded in the token. */
    int extractUserId(String token);

    /** Returns the username encoded in the token. */
    String extractUsername(String token);

    /**
     * Returns the sessionId (sid claim) encoded in the token. Used by the
     * cart-binding logic on Guest→Member promotion (D9a / Phase 4.1b).
     */
    String extractSessionId(String token);

    /** Quick expiry check; returns {@code true} for expired, revoked, or malformed tokens. */
    boolean isExpired(String token);

    /** Epoch millis at which the token expires (read from the {@code exp} claim). UC-12. */
    long extractExpiration(String token);

    /** Invalidates a token explicitly via the denylist. Idempotent. UC-14. */
    void invalidate(String token);

    /** Whether the given user has a currently-active session. UC-35/36. */
    boolean isOnline(int userId);

    /**
     * Returns the userId if the token is valid, {@code Optional.empty()} otherwise.
     * Convenience wrapper for callers that prefer not to handle exceptions.
     */
    Optional<Integer> tryExtractUserId(String token);
}
