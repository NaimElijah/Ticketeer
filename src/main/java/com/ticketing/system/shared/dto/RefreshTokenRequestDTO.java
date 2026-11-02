package com.ticketing.system.shared.dto;

// Input for token-refresh flow per lecture 2's JWT pattern.
// UC-12 (token expired → client hits refresh endpoint).
public record RefreshTokenRequestDTO(
    String refreshToken
) {

    /**
     * Leaves out the refresh token, which is a credential, so a logged request or an exception
     * message built from one does not carry it (SLR.2).
     */
    @Override
    public String toString() {
        return "RefreshTokenRequestDTO[]";
    }
}
