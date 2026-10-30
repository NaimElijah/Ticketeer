package com.ticketing.system.identity.adapter.out.security;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import com.ticketing.system.identity.application.port.out.PasswordHasher;

/**
 * BCrypt-backed implementation of {@link PasswordHasher}. UC-11 / UC-12.
 *
 * <p>Delegates to the application's {@link PasswordEncoder} bean ({@code shared/config/SecurityConfig},
 * a BCrypt encoder at the default cost factor 10), so the encoder is configured in one place. Each
 * {@link #hash} call generates a fresh salt; the salt is embedded in the returned string so
 * verification only needs the stored value.
 */
@Component
public class BcryptPasswordHasher implements PasswordHasher {

    private final PasswordEncoder encoder;

    /**
     * @param encoder the encoder every hash and verification goes through
     */
    public BcryptPasswordHasher(PasswordEncoder encoder) {
        this.encoder = encoder;
    }

    /** Hashes a raw password with a fresh random salt. UC-11. */
    @Override
    public String hash(String rawPassword) {
        return encoder.encode(rawPassword);
    }

    /**
     * Verifies a candidate raw password against a stored BCrypt hash. UC-12.
     *
     * @return {@code false} when either argument is {@code null}, without consulting the encoder
     */
    @Override
    public boolean matches(String rawPassword, String storedHash) {
        if (rawPassword == null || storedHash == null) return false;
        return encoder.matches(rawPassword, storedHash);
    }
}
