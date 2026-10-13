package com.ticketing.system.shared.dto;

// Used by any future profile-edit / forced-password-change flow.
// Currently UC-15 is Cancelled, but this DTO is the natural input shape if it returns.
// Both passwords are raw; the service hashes before persisting.
public record PasswordChangeDTO(
    String currentRawPassword,
    String newRawPassword
) {}
