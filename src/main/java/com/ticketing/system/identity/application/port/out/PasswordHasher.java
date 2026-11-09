package com.ticketing.system.identity.application.port.out;

// Port for password hashing. Implemented by BcryptPasswordHasher
// (lecture 2's recommended BCrypt approach). Used by AuthenticationService, by governance's
// SystemAdminService when it creates the default admin, and by the boot-time admin password rotation.
public interface PasswordHasher {

    // UC-11 — hash a raw password before persisting.
    String hash(String rawPassword);

    // UC-12 — verify a raw password against a stored hash.
    boolean matches(String rawPassword, String storedHash);
}
