package com.ticketing.system.shared.dto;

// Input to AuthenticationService.refreshToken() (UC-12), which always throws
// UnsupportedOperationException: the refresh-token flow is deferred from V1.
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
