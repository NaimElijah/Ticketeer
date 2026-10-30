package com.ticketing.system.shared.dto;

// Input to AuthenticationService.login() (UC-12).
//
// guestSessionId is REQUIRED — login is always a promotion of an existing
// Guest session into a Member session (per spec II.1.5 / D10a). A login
// without a guestSessionId raises GuestSessionRequiredException.
public record LoginRequestDTO(
    String username,
    String rawPassword,
    String guestSessionId
) {

    /**
     * Leaves out the password and the guest session id, which is the guest's credential, so a
     * logged request or an exception message built from one carries neither (SLR.2).
     */
    @Override
    public String toString() {
        return "LoginRequestDTO[username=" + username + "]";
    }
}
