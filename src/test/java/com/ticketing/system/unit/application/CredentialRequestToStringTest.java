package com.ticketing.system.unit.application;

import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

import com.ticketing.system.identity.application.dto.RegisterRequestDTO;
import com.ticketing.system.shared.dto.LoginRequestDTO;

/**
 * The sign-in and registration requests carry a raw password. A record's generated {@code toString}
 * prints every component, so one logged request, or one exception message built from it, would put
 * the password in the log (#185). Their {@code toString} must leave the password out, and with it
 * the guest session id, which is the guest's credential, and the email, which is personal data.
 */
class CredentialRequestToStringTest {

    private static final String RAW_PASSWORD = "S3cret-Passw0rd-under-test";
    private static final String GUEST_SESSION_ID = "6d1e0b3a-guest-session-under-test";
    private static final String EMAIL = "alice.under.test@example.com";

    @Test
    void GivenLoginRequest_WhenToString_ThenThePasswordIsAbsent() {
        LoginRequestDTO request = new LoginRequestDTO("alice", RAW_PASSWORD, GUEST_SESSION_ID);

        String rendered = request.toString();

        assertFalse(rendered.contains(RAW_PASSWORD), "LoginRequestDTO.toString() exposed the password: " + rendered);
        assertFalse(rendered.contains(GUEST_SESSION_ID),
                "LoginRequestDTO.toString() exposed the guest session id: " + rendered);
    }

    @Test
    void GivenRegisterRequest_WhenToString_ThenThePasswordIsAbsent() {
        RegisterRequestDTO request = new RegisterRequestDTO("alice", EMAIL, RAW_PASSWORD, GUEST_SESSION_ID, 30);

        String rendered = request.toString();

        assertFalse(rendered.contains(RAW_PASSWORD),
                "RegisterRequestDTO.toString() exposed the password: " + rendered);
        assertFalse(rendered.contains(GUEST_SESSION_ID),
                "RegisterRequestDTO.toString() exposed the guest session id: " + rendered);
        assertFalse(rendered.contains(EMAIL), "RegisterRequestDTO.toString() exposed the email: " + rendered);
    }
}
