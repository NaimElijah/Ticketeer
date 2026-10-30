package com.ticketing.system.unit.infrastructure.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.ticketing.system.identity.adapter.out.security.BcryptPasswordHasher;

class BcryptPasswordHasherTest {

    private BcryptPasswordHasher hasher;

    @BeforeEach
    void setUp() {
        hasher = new BcryptPasswordHasher(new BCryptPasswordEncoder());
    }

    @Test
    void hash_producesNonNullNonEmptyString() {
        String result = hasher.hash("Password1");
        assertNotNull(result);
        assertTrue(result.length() > 0);
    }

    @Test
    void hash_producesBcryptFormat() {
        String result = hasher.hash("Password1");
        // BCrypt strings start with $2a$, $2b$, or $2y$ followed by the cost factor.
        assertTrue(result.startsWith("$2a$") || result.startsWith("$2b$") || result.startsWith("$2y$"),
            "expected BCrypt format prefix, got: " + result);
    }

    @Test
    void hash_sameInputProducesDifferentOutput() {
        // Each call uses a fresh random salt, so identical inputs hash to different strings.
        String first = hasher.hash("Password1");
        String second = hasher.hash("Password1");
        assertNotEquals(first, second);
    }

    @Test
    void hash_doesNotContainRawPassword() {
        String raw = "Password1";
        String result = hasher.hash(raw);
        assertTrue(!result.contains(raw), "hash should not contain the raw password");
    }

    @Test
    void matches_returnsTrueForCorrectPassword() {
        String stored = hasher.hash("Password1");
        assertTrue(hasher.matches("Password1", stored));
    }

    @Test
    void matches_returnsFalseForWrongPassword() {
        String stored = hasher.hash("Password1");
        assertFalse(hasher.matches("WrongPassword", stored));
    }

    @Test
    void matches_returnsFalseForNullRawPassword() {
        String stored = hasher.hash("Password1");
        assertFalse(hasher.matches(null, stored));
    }

    @Test
    void matches_returnsFalseForNullStoredHash() {
        assertFalse(hasher.matches("Password1", null));
    }

    // #34: the hasher uses the PasswordEncoder bean it is given (shared SecurityConfig), not one of its own.
    @Test
    void GivenAnInjectedEncoder_WhenHash_ThenTheHashComesFromThatEncoder() {
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(encoder.encode("Password1")).thenReturn("encoded-by-the-injected-encoder");

        String result = new BcryptPasswordHasher(encoder).hash("Password1");

        assertEquals("encoded-by-the-injected-encoder", result,
            "hash must return what the injected PasswordEncoder produced");
    }

    @Test
    void GivenAnInjectedEncoder_WhenMatches_ThenTheVerdictComesFromThatEncoder() {
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(encoder.matches("Password1", "stored-hash")).thenReturn(true);

        boolean result = new BcryptPasswordHasher(encoder).matches("Password1", "stored-hash");

        assertTrue(result, "matches must return the injected PasswordEncoder's verdict");
        verify(encoder).matches("Password1", "stored-hash");
    }

    @Test
    void GivenANullStoredHash_WhenMatches_ThenFalseWithoutConsultingTheEncoder() {
        PasswordEncoder encoder = mock(PasswordEncoder.class);

        boolean result = new BcryptPasswordHasher(encoder).matches("Password1", null);

        assertFalse(result);
        verify(encoder, never()).matches(any(), any());
    }
}
