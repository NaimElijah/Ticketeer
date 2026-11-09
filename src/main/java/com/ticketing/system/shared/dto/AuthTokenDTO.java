package com.ticketing.system.shared.dto;

/**
 * A signed-in session's token. Carried by {@link LoginDTO} for a member's login (UC-12) and
 * returned by {@code AuthenticationService.signInAsAdmin} for an admin (upstream#290).
 *
 * @param token                the session token, which is the session's credential; {@link #toString()}
 *                             leaves it out
 * @param expiresAtEpochMillis when the token expires, in milliseconds since the epoch, to keep the DTO
 *                             primitive-only
 * @param userId               the member's id, or the admin's id for an admin token
 * @param isAdmin              whether the token was issued for the admin pool, so the presentation layer
 *                             need not guess from the username; {@code false} for a member token
 */
public record AuthTokenDTO(
    String token,
    long expiresAtEpochMillis,
    int userId,
    String username,
    boolean isAdmin
) {
    /** Member token — isAdmin defaults to false. */
    public AuthTokenDTO(String token, long expiresAtEpochMillis, int userId, String username) {
        this(token, expiresAtEpochMillis, userId, username, false);
    }

    /**
     * Leaves out the token, which is the session's credential, so a logged token or an exception
     * message built from one does not carry it (SLR.2).
     */
    @Override
    public String toString() {
        return "AuthTokenDTO[expiresAtEpochMillis=" + expiresAtEpochMillis
                + ", userId=" + userId
                + ", username=" + username
                + ", isAdmin=" + isAdmin + "]";
    }
}
