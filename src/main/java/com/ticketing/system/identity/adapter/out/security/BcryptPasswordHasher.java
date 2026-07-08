package com.ticketing.system.identity.adapter.out.security;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import com.ticketing.system.identity.application.port.out.PasswordHasher;

/**
 * BCrypt-backed implementation of {@link PasswordHasher}. UC-11 / UC-12.
 *
 * <p>Uses Spring Security's {@link BCryptPasswordEncoder} with the default cost
 * factor (10). Each {@link #hash} call generates a fresh salt; the salt is
 * embedded in the returned string so verification only needs the stored value.
 */
@Component
public class BcryptPasswordHasher implements PasswordHasher {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    /** Hashes a raw password with a fresh random salt. UC-11. */
    @Override
    public String hash(String rawPassword) {
        return encoder.encode(rawPassword);
    }

    /** Verifies a candidate raw password against a stored BCrypt hash. UC-12. */
    @Override
    public boolean matches(String rawPassword, String storedHash) {
        if (rawPassword == null || storedHash == null) return false;
        return encoder.matches(rawPassword, storedHash);
    }
}
