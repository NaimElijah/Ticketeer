package com.ticketing.system.integration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.ticketing.system.identity.application.dto.RegisterRequestDTO;
import com.ticketing.system.identity.application.port.out.UserRepository;
import com.ticketing.system.identity.application.service.AuthenticationService;
import com.ticketing.system.shared.exception.DuplicateUsernameException;

/**
 * UC-11 / #76: a member account must not take a name the platform reserves, whoever calls
 * {@code register} (#185). The reserved names come from {@code identity.reserved-usernames}, and the
 * configured {@code platform.admin.username} is always reserved too. Matching ignores case and
 * surrounding whitespace, on both the configured names and the requested one.
 *
 * <p>A reserved name is refused as a taken one: {@code DuplicateUsernameException}, or a subtype of
 * it if the refusal needs its own type. This class sets its own reserved list and admin username, so
 * the names it expects to be refused cannot come from a hard-coded set.
 */
@SpringBootTest(properties = {
        "identity.reserved-usernames=Box.Office, support",
        "platform.admin.username=Root.Admin"})
@ActiveProfiles("test")
class ReservedUsernameRegistrationJpaTest {

    private static final String PASSWORD = "Password1";
    private static final int AGE = 30;
    // Every request gets its own email, so a refusal can only come from the username.
    private static final AtomicInteger EMAILS = new AtomicInteger();

    @Autowired
    private AuthenticationService authService;

    @Autowired
    private UserRepository userRepository;

    @Test
    void GivenReservedUsername_WhenRegister_ThenRejected() {
        assertRefused("support");
    }

    @Test
    void GivenReservedUsernameInAnotherCase_WhenRegister_ThenRejected() {
        assertRefused("SUPPORT");
    }

    @Test
    void GivenReservedUsernameConfiguredInMixedCase_WhenRegisterLowerCase_ThenRejected() {
        assertRefused("box.office");
    }

    @Test
    void GivenReservedUsernameWithSurroundingWhitespace_WhenRegister_ThenRejected() {
        assertRefused("  support  ");
    }

    @Test
    void GivenPlatformAdminUsername_WhenRegister_ThenRejected() {
        assertRefused("root.admin");
    }

    @Test
    void GivenUnreservedUsername_WhenRegister_ThenRegistered() {
        assertDoesNotThrow(() -> authService.register(request("box.office.fan")));

        assertTrue(userRepository.existsByUsername("box.office.fan"));
    }

    private void assertRefused(String username) {
        assertThrows(DuplicateUsernameException.class, () -> authService.register(request(username)),
                "'" + username + "' is reserved and must not be registered");
        String stored = username.trim();
        assertFalse(userRepository.existsByUsername(stored)
                        || userRepository.existsByUsername(stored.toLowerCase(Locale.ROOT)),
                "no user may be created under the reserved name '" + username + "'");
    }

    private RegisterRequestDTO request(String username) {
        String email = "registrant" + EMAILS.incrementAndGet() + "@example.com";
        return new RegisterRequestDTO(username, email, PASSWORD, authService.startGuestSession().sessionId(), AGE);
    }
}
