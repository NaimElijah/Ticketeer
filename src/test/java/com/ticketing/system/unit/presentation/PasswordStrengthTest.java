package com.ticketing.system.unit.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ticketing.system.ui.presenters.auth.PasswordStrength;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.mockito.Mockito.mock;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.params.provider.ValueSource;
import com.ticketing.system.identity.application.dto.RegisterRequestDTO;
import com.ticketing.system.identity.application.port.out.AdminRepository;
import com.ticketing.system.identity.application.port.out.CartRestorationPort;
import com.ticketing.system.identity.application.port.out.PasswordHasher;
import com.ticketing.system.identity.application.port.out.SessionManager;
import com.ticketing.system.identity.application.port.out.SessionRepository;
import com.ticketing.system.identity.application.port.out.UserRepository;
import com.ticketing.system.identity.application.service.AuthenticationService;
import com.ticketing.system.identity.config.IdentityProperties;
import com.ticketing.system.identity.config.LockoutProperties;
import com.ticketing.system.identity.config.SessionProperties;
import com.ticketing.system.notifications.application.service.NotificationDispatchService;
import com.ticketing.system.shared.exception.GuestSessionRequiredException;
import com.ticketing.system.shared.exception.WeakPasswordException;
import com.ticketing.system.shared.metrics.ISystemMetrics;
import com.ticketing.system.testutil.TestTransactions;
import java.nio.charset.StandardCharsets;

class PasswordStrengthTest {

    @Test
    void GivenEmptyOrNullInput_WhenOf_ThenIsNone() {
        assertEquals(PasswordStrength.NONE, PasswordStrength.of(""));
        assertEquals(PasswordStrength.NONE, PasswordStrength.of(null));
    }

    @ParameterizedTest
    @CsvSource({
        // pw, expected
        "short1,           WEAK",        // < 8 chars
        "letters_only,     WEAK",        // no digit
        "12345678,         WEAK",        // no letter
        "password1,        FAIR",        // 9 chars, letter + digit — minimum bar
        "Password1!withMix, STRONG",     // 12+ chars, letter, digit, upper, lower, special
        "Aa1!Bb2@Cc3#,     STRONG",      // 12 chars, every variety
        "Aa1Bbcdef,        FAIR",        // 9 chars, mixed-case + digit but no special and < 12
        "abcdef1,          WEAK",        // 7 chars: one short of the server's minimum
        "abcdefg1,         FAIR",        // 8 chars: the server's minimum
        "Aa1!Bb2@Cc3,      FAIR",        // 11 chars, every variety: one short of STRONG
    })
    void GivenWeakFairAndStrongPasswords_WhenOf_ThenReturnsExpectedTier(String pw, PasswordStrength expected) {
        assertEquals(expected, PasswordStrength.of(pw));
    }

    @Test
    void GivenPasswordsOnBothSidesOfTheServerRules_WhenPassesServerRules_ThenAcceptsOnlyLongEnoughPasswordsWithALetterAndADigit() {
        // Mirrors AuthenticationService.validatePassword:
        //  - rejects null / < 8 chars
        //  - requires letter AND digit
        assertFalse(PasswordStrength.passesServerRules(null));
        assertFalse(PasswordStrength.passesServerRules(""));
        assertFalse(PasswordStrength.passesServerRules("short1"));
        assertFalse(PasswordStrength.passesServerRules("letters_only"));
        assertFalse(PasswordStrength.passesServerRules("12345678"));

        assertTrue(PasswordStrength.passesServerRules("password1"));
        assertTrue(PasswordStrength.passesServerRules("Aa1!Bb2@Cc3#"));
    }

    @Test
    void GivenTheStrengthTiers_WhenProgressValue_ThenIsOrdered() {
        assertTrue(PasswordStrength.NONE.progressValue() < PasswordStrength.WEAK.progressValue());
        assertTrue(PasswordStrength.WEAK.progressValue() < PasswordStrength.FAIR.progressValue());
        assertTrue(PasswordStrength.FAIR.progressValue() < PasswordStrength.STRONG.progressValue());
    }

    @Test
    void GivenTheStrengthTiers_WhenLabel_ThenLabelsAreUserFacing() {
        assertEquals("", PasswordStrength.NONE.label());
        assertEquals("Weak", PasswordStrength.WEAK.label());
        assertEquals("Fair", PasswordStrength.FAIR.label());
        assertEquals("Strong", PasswordStrength.STRONG.label());
    }

    // ---- the meter and the server judge a password alike (#189) ----
    // passesServerRules promises what AuthenticationService.register enforces, so one data set runs through
    // both: a hard-coded copy of the rules could drift from the server's and stay green.

    // register checks the email, then the password; with no guest session it stops right after.
    private static final AuthenticationService SERVER = new AuthenticationService(mock(UserRepository.class),
            mock(PasswordHasher.class), mock(SessionManager.class), mock(CartRestorationPort.class),
            mock(NotificationDispatchService.class), mock(SessionRepository.class), mock(ISystemMetrics.class),
            Clock.fixed(Instant.parse("2027-07-15T17:00:00Z"), ZoneOffset.UTC), mock(AdminRepository.class),
            new SessionProperties(1440, 30), new LockoutProperties(5, 15L), new IdentityProperties(List.of(), "admin"),
            TestTransactions.recordingManager());

    // The longest password register accepts is 72 bytes in UTF-8, so the boundary runs in ASCII, one byte a
    // character, and in Hebrew, two bytes a letter: 35 letters and two digits are 72 bytes.
    private static final String ASCII_24_BYTES = "a1a1a1a1a1a1a1a1a1a1a1a1";
    private static final String ASCII_72_BYTES = ASCII_24_BYTES + ASCII_24_BYTES + ASCII_24_BYTES;
    private static final String ASCII_73_BYTES = ASCII_72_BYTES + "a";
    private static final String HEBREW_7_LETTERS = "\u05e1\u05d9\u05e1\u05de\u05d0\u05d5\u05ea";
    private static final String HEBREW_35_LETTERS =
            HEBREW_7_LETTERS + HEBREW_7_LETTERS + HEBREW_7_LETTERS + HEBREW_7_LETTERS + HEBREW_7_LETTERS;
    private static final String HEBREW_72_BYTES = HEBREW_35_LETTERS + "12";
    private static final String HEBREW_73_BYTES = HEBREW_35_LETTERS + "123";

    @ParameterizedTest
    @ValueSource(strings = {"", "short1", "abcdef1", "abcdefg1", "abcdefgh", "12345678", "password1", "abcdefghij1",
            "Aa1!Bb2@Cc3#", "p\u00e4ssw\u00f6rd1", "pass word 1", ASCII_72_BYTES, ASCII_73_BYTES, HEBREW_72_BYTES,
            HEBREW_73_BYTES})
    void GivenAPassword_WhenTheMeterAndTheServerJudgeIt_ThenTheyAgree(String password) {
        assertEquals(serverAccepts(password), PasswordStrength.passesServerRules(password),
                "the meter and the server disagree on \"" + password + "\"");
    }

    @Test
    void GivenNoPassword_WhenTheMeterAndTheServerJudgeIt_ThenBothRefuseIt() {
        assertFalse(serverAccepts(null), "the server refuses no password");
        assertFalse(PasswordStrength.passesServerRules(null), "the meter refuses no password");
    }

    private static boolean serverAccepts(String password) {
        try {
            SERVER.register(new RegisterRequestDTO("dana", "dana@example.com", password, null, 30));
            throw new AssertionError("register must stop at the missing guest session");
        } catch (WeakPasswordException e) {
            return false;
        } catch (GuestSessionRequiredException e) {
            return true;
        }
    }

    // Register refuses a password over 72 bytes in UTF-8, the most BCrypt reads
    // (AuthenticationService.MAX_PASSWORD_UTF8_BYTES), so the meter must not call such a password acceptable
    // (#189). The first is 73 ASCII characters; the second is 63 characters and 77 bytes.
    @ParameterizedTest
    @ValueSource(strings = {
        "a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a",
        "p\u00e4ssw\u00f6rd1p\u00e4ssw\u00f6rd1p\u00e4ssw\u00f6rd1p\u00e4ssw\u00f6rd1p\u00e4ssw\u00f6rd1p\u00e4ssw\u00f6rd1p\u00e4ssw\u00f6rd1"})
    void GivenAPasswordOver72BytesInUtf8_WhenTheMeterAndTheServerJudgeIt_ThenBothRefuseIt(String password) {
        assertFalse(serverAccepts(password), "precondition: the server refuses it");
        assertFalse(PasswordStrength.passesServerRules(password),
                "the meter accepts a password of " + password.getBytes(StandardCharsets.UTF_8).length
                        + " UTF-8 bytes that the server refuses");
    }
}
