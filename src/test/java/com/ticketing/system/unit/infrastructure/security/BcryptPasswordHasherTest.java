package com.ticketing.system.unit.infrastructure.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder.SecretKeyFactoryAlgorithm;

import com.ticketing.system.identity.adapter.out.security.BcryptPasswordHasher;

class BcryptPasswordHasherTest {

    // Below the default cost of 10, so a hash that carries it can only come from the injected encoder.
    private static final int INJECTED_COST = 4;
    private static final String INJECTED_COST_PREFIX = "$2a$04$";
    // A fast PBKDF2 encoder: its hashes are no BCrypt hashes, so only it can accept them.
    private static final int PBKDF2_SALT_BYTES = 16;
    private static final int PBKDF2_ITERATIONS = 1;

    private BcryptPasswordHasher hasher;

    @BeforeEach
    void setUp() {
        hasher = new BcryptPasswordHasher(new BCryptPasswordEncoder());
    }

    @Test
    void GivenARawPassword_WhenHash_ThenProducesNonNullNonEmptyString() {
        String result = hasher.hash("Password1");
        assertNotNull(result);
        assertTrue(result.length() > 0);
    }

    @Test
    void GivenARawPassword_WhenHash_ThenProducesBcryptFormat() {
        String result = hasher.hash("Password1");
        // BCrypt strings start with $2a$, $2b$, or $2y$ followed by the cost factor.
        assertTrue(result.startsWith("$2a$") || result.startsWith("$2b$") || result.startsWith("$2y$"),
            "expected BCrypt format prefix, got: " + result);
    }

    @Test
    void GivenTheSameRawPasswordTwice_WhenHash_ThenProducesDifferentOutput() {
        // Each call uses a fresh random salt, so identical inputs hash to different strings.
        String first = hasher.hash("Password1");
        String second = hasher.hash("Password1");
        assertNotEquals(first, second);
    }

    @Test
    void GivenARawPassword_WhenHash_ThenDoesNotContainRawPassword() {
        String raw = "Password1";
        String result = hasher.hash(raw);
        assertTrue(!result.contains(raw), "hash should not contain the raw password");
    }

    @Test
    void GivenTheCorrectPassword_WhenMatches_ThenReturnsTrue() {
        String stored = hasher.hash("Password1");
        assertTrue(hasher.matches("Password1", stored));
    }

    @Test
    void GivenAWrongPassword_WhenMatches_ThenReturnsFalse() {
        String stored = hasher.hash("Password1");
        assertFalse(hasher.matches("WrongPassword", stored));
    }

    @Test
    void GivenANullRawPassword_WhenMatches_ThenReturnsFalse() {
        String stored = hasher.hash("Password1");
        assertFalse(hasher.matches(null, stored));
    }

    @Test
    void GivenANullStoredHash_WhenMatches_ThenReturnsFalse() {
        assertFalse(hasher.matches("Password1", null));
    }

    // #34: the hasher uses the PasswordEncoder bean it is given (shared SecurityConfig), not one of its own.
    @Test
    void GivenAnInjectedEncoder_WhenHash_ThenTheHashComesFromThatEncoder() {
        String result = new BcryptPasswordHasher(new BCryptPasswordEncoder(INJECTED_COST)).hash("Password1");

        assertTrue(result.startsWith(INJECTED_COST_PREFIX),
            "hash must carry the injected encoder's cost of " + INJECTED_COST + ", got: " + result);
    }

    @Test
    void GivenAnInjectedEncoder_WhenMatches_ThenTheVerdictComesFromThatEncoder() {
        PasswordEncoder pbkdf2 = new Pbkdf2PasswordEncoder("", PBKDF2_SALT_BYTES, PBKDF2_ITERATIONS,
            SecretKeyFactoryAlgorithm.PBKDF2WithHmacSHA256);
        String stored = pbkdf2.encode("Password1");

        boolean result = new BcryptPasswordHasher(pbkdf2).matches("Password1", stored);

        assertTrue(result, "matches must return the injected encoder's verdict on its own PBKDF2 hash");
    }

    @Test
    void GivenANullStoredHash_WhenMatches_ThenFalseWithoutConsultingTheEncoder() {
        PasswordEncoder encoder = mock(PasswordEncoder.class);

        boolean result = new BcryptPasswordHasher(encoder).matches("Password1", null);

        assertFalse(result);
        verify(encoder, never()).matches(any(), any());
    }
}
