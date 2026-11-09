package com.ticketing.system.identity.application.dto;

/**
 * Input to {@code AuthenticationService.register()} (UC-11).
 *
 * @param username       the requested username
 * @param email          the contact address, unique among members
 * @param rawPassword    the password as typed; hashed in the application service before it reaches
 *                       the {@code User} entity
 * @param guestSessionId the raw id of the visitor's active Guest session (D10a): required, because
 *                       register must be called from one. The session stays Guest after register
 *                       completes; login is what promotes it (spec II.1.4)
 * @param age            the registrant's age in whole years
 */
public record RegisterRequestDTO(
    String username,
    String email,
    String rawPassword,
    String guestSessionId,
    int age
) {

    /**
     * Leaves out the password, the guest session id (the guest's credential), the email address and the
     * age, so a logged request or an exception message built from one carries none of them (SLR.2).
     */
    @Override
    public String toString() {
        return "RegisterRequestDTO[username=" + username + "]";
    }
}
