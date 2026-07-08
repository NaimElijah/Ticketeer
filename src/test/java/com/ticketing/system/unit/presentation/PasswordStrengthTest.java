package com.ticketing.system.unit.presentation;
import com.ticketing.system.identity.application.service.AuthenticationService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ticketing.system.ui.presenters.auth.PasswordStrength;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PasswordStrengthTest {

    @Test
    void emptyInput_isNone() {
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
    })
    void of_returnsExpectedTier(String pw, PasswordStrength expected) {
        assertEquals(expected, PasswordStrength.of(pw));
    }

    @Test
    void passesServerRules_matchesAuthenticationServiceValidation() {
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
    void progressValue_isOrdered() {
        assertTrue(PasswordStrength.NONE.progressValue() < PasswordStrength.WEAK.progressValue());
        assertTrue(PasswordStrength.WEAK.progressValue() < PasswordStrength.FAIR.progressValue());
        assertTrue(PasswordStrength.FAIR.progressValue() < PasswordStrength.STRONG.progressValue());
    }

    @Test
    void labels_areUserFacing() {
        assertEquals("", PasswordStrength.NONE.label());
        assertEquals("Weak", PasswordStrength.WEAK.label());
        assertEquals("Fair", PasswordStrength.FAIR.label());
        assertEquals("Strong", PasswordStrength.STRONG.label());
    }
}
