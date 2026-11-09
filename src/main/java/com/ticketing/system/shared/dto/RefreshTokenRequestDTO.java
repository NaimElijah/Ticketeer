package com.ticketing.system.shared.dto;

/**
 * Input to {@code AuthenticationService.refreshToken} (UC-12), which always throws
 * {@code UnsupportedOperationException}: the refresh-token flow is deferred from V1.
 *
 * @param refreshToken the refresh token, which is a credential; {@link #toString()} leaves it out
 */
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
