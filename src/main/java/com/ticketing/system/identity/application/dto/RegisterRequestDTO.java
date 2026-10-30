package com.ticketing.system.identity.application.dto;

// Input to AuthenticationService.register() (UC-11).
// Raw password — hashed in the application service before reaching the User entity.
//
// guestSessionId is REQUIRED (D10a). Register must be called from an active
// Guest session; the session stays Guest after register completes — login is
// what promotes it. See spec II.1.4.
public record RegisterRequestDTO(
    String username,
    String email,
    String rawPassword,
    String guestSessionId,
    int age
) {

    /**
     * Leaves out the password, the guest session id (the guest's credential) and the email address,
     * so a logged request or an exception message built from one carries none of them (SLR.2).
     */
    @Override
    public String toString() {
        return "RegisterRequestDTO[username=" + username + ", age=" + age + "]";
    }
}
