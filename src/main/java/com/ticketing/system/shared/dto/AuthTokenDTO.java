package com.ticketing.system.shared.dto;

// Returned by AuthenticationService.login() (UC-12) and signInAsAdmin() (#290).
// 'expiresAt' is epoch millis to keep the DTO primitive-only.
// 'isAdmin' lets the presentation layer know the pool the token was issued for without
// consulting a hardcoded username set; member tokens default it to false.
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
}
