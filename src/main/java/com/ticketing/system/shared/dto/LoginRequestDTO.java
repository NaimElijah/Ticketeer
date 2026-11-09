package com.ticketing.system.shared.dto;

/**
 * Input to {@code AuthenticationService.login} (UC-12).
 *
 * <p>{@code guestSessionId} is required: login is always a promotion of an existing Guest session
 * into a Member session (per spec II.1.5 / D10a). A login without one raises
 * {@code GuestSessionRequiredException}.
 *
 * @param rawPassword    the password as typed; {@link #toString()} leaves it out
 * @param guestSessionId the guest session to promote, which is the guest's credential
 */
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
