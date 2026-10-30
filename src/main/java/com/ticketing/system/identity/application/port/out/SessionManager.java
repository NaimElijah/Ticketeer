package com.ticketing.system.identity.application.port.out;

import java.util.Optional;

import com.ticketing.system.identity.domain.Session;

/**
 * Port for session / token management. Implemented in Infrastructure by
 * {@code JwtSessionManager}. Used by {@code AuthenticationService} (UC-12/14)
 * and {@code NotificationDispatchService} (UC-35/36 — online check).
 *
 * <p>Every token names one session row, and is accepted only while that row exists, is
 * unexpired, belongs to the token's subject, and is of the kind the token's role claims.
 * Member ids and admin ids share one integer space (ADR-012), so the readers come in two
 * families that never accept each other's tokens:
 * <ul>
 *   <li><b>Member readers</b> ({@link #validateToken}, {@link #extractUserId},
 *       {@link #tryExtractUserId}, {@link #extractUsername}, and the JWT path of
 *       {@link #validateCredential}) accept member tokens only. They treat an admin token
 *       exactly as a revoked token.</li>
 *   <li><b>Admin readers</b> ({@link #validateAdminToken}, {@link #extractAdminId},
 *       {@link #isAdminToken}) accept admin tokens only.</li>
 * </ul>
 * {@link #extractSessionId}, {@link #extractExpiration}, {@link #isExpired} and
 * {@link #invalidate} work on a token of either kind.
 */
public interface SessionManager {

    /** Issues a member token after successful login. UC-12. */
    String generateToken(int userId, String username);

    /**
     * Issues a member JWT bound to an existing Session row. Used by promote-on-login
     * (Phase 3 of the unified-session rework) so the Guest's sessionId is
     * preserved into the Member flow and any attached cart survives.
     *
     * @throws IllegalArgumentException if {@code session} is null
     * @throws IllegalStateException if {@code session} is not a Member session (a Guest or an
     *     Admin session)
     */
    String generateTokenForSession(Session session, String username);

    /**
     * Admin sign-in (#290): saves an Admin session row and issues a JWT for it that carries the
     * ADMIN role claim, so no member reader accepts it and no admin reader accepts a member token.
     */
    String generateAdminToken(int adminId, String username);

    /**
     * Whether the token is a live admin token: the ADMIN role claim on a live Admin session row.
     *
     * @return {@code false} for a member token, and for a malformed, revoked or expired token
     */
    boolean isAdminToken(String token);

    /**
     * Validates a member token. UC-12.
     *
     * @return {@code true} for a well-formed, signed, unexpired member token whose session row
     *     is live; {@code false} only for {@code null} / blank input
     * @throws com.ticketing.system.shared.exception.InvalidTokenException
     *     malformed, bad signature, revoked, or not a member token (an admin token included)
     * @throws com.ticketing.system.shared.exception.SessionExpiredException
     *     past the {@code exp} claim or the session row's expiry
     */
    boolean validateToken(String token);

    /**
     * Validates an admin token: the admin-reader counterpart of {@link #validateToken}.
     *
     * @return {@code true} for a well-formed, signed, unexpired admin token whose Admin session
     *     row is live; {@code false} only for {@code null} / blank input
     * @throws com.ticketing.system.shared.exception.InvalidTokenException
     *     malformed, bad signature, revoked, or not an admin token (a member token included)
     * @throws com.ticketing.system.shared.exception.SessionExpiredException
     *     past the {@code exp} claim or the session row's expiry
     */
    boolean validateAdminToken(String token);

    /**
     * Validates a buyer credential: a member JWT, or a Guest session's raw sessionId.
     * Returns {@code true} iff the credential is a live member token (see
     * {@link #validateToken}) or the id of a live, non-expired Guest session row. An admin
     * token, and the raw id of a Member or Admin session, are not buyer credentials.
     *
     * <p>Used by guest-friendly endpoints (UC-3 / UC-8 catalog browse) where
     * the caller may not have a JWT. Never throws — converts any internal
     * validation failure to {@code false}.
     */
    boolean validateCredential(String credential);

    /**
     * Returns the member id a member token names.
     *
     * @throws com.ticketing.system.shared.exception.InvalidTokenException
     *     for any token {@link #validateToken} rejects as invalid, an admin token included
     * @throws com.ticketing.system.shared.exception.SessionExpiredException
     *     for an expired token
     */
    int extractUserId(String token);

    /**
     * Returns the admin id an admin token names: the admin-reader counterpart of
     * {@link #extractUserId}.
     *
     * @throws com.ticketing.system.shared.exception.InvalidTokenException
     *     for any token {@link #validateAdminToken} rejects as invalid, a member token included
     * @throws com.ticketing.system.shared.exception.SessionExpiredException
     *     for an expired token
     */
    int extractAdminId(String token);

    /**
     * Returns the username a member token carries. Member tokens only, with the exceptions of
     * {@link #extractUserId}.
     */
    String extractUsername(String token);

    /**
     * Returns the sessionId (sid claim) of a live member or admin token. Used by the
     * cart-binding logic on Guest→Member promotion (D9a / Phase 4.1b).
     */
    String extractSessionId(String token);

    /** Quick expiry check; returns {@code true} for expired, revoked, or malformed tokens of either kind. */
    boolean isExpired(String token);

    /**
     * Epoch millis at which a live member or admin token expires (read from the {@code exp}
     * claim). UC-12.
     */
    long extractExpiration(String token);

    /**
     * Revokes a member or admin token by deleting its session row, even when the token has
     * expired. Idempotent. UC-14.
     */
    void invalidate(String token);

    /** Whether the given user has a currently-active session. UC-35/36. */
    boolean isOnline(int userId);

    /**
     * Returns the member id if the token is a valid member token, {@code Optional.empty()}
     * otherwise (an admin token included). Convenience wrapper for callers that prefer not to
     * handle exceptions.
     */
    Optional<Integer> tryExtractUserId(String token);
}
