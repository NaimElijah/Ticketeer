package com.ticketing.system.identity.application.port.out;
import com.ticketing.system.identity.application.service.AuthenticationService;

// Port for password hashing. Implemented in Infrastructure by BcryptPasswordHasher
// (lecture 2's recommended BCrypt approach). Used by AuthenticationService.
public interface PasswordHasher {

    // UC-11 — hash a raw password before persisting.
    String hash(String rawPassword);

    // UC-12 — verify a raw password against a stored hash.
    boolean matches(String rawPassword, String storedHash);
}
