package com.ticketing.system.unit.application;

import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

import com.ticketing.system.identity.application.dto.RegisterRequestDTO;
import com.ticketing.system.shared.dto.LoginRequestDTO;

/**
 * The sign-in and registration requests carry a raw password. A record's generated {@code toString}
 * prints every component, so one logged request, or one exception message built from it, would put
 * the password in the log (#185). Their {@code toString} must leave the password out.
 */
class CredentialRequestToStringTest {

    private static final String RAW_PASSWORD = "S3cret-Passw0rd-under-test";

    @Test
    void GivenLoginRequest_WhenToString_ThenThePasswordIsAbsent() {
        LoginRequestDTO request = new LoginRequestDTO("alice", RAW_PASSWORD, "guest-1");

        String rendered = request.toString();

        assertFalse(rendered.contains(RAW_PASSWORD), "LoginRequestDTO.toString() exposed the password: " + rendered);
    }

    @Test
    void GivenRegisterRequest_WhenToString_ThenThePasswordIsAbsent() {
        RegisterRequestDTO request = new RegisterRequestDTO("alice", "alice@example.com", RAW_PASSWORD, "guest-1", 30);

        String rendered = request.toString();

        assertFalse(rendered.contains(RAW_PASSWORD),
                "RegisterRequestDTO.toString() exposed the password: " + rendered);
    }
}
