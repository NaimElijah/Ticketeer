package com.ticketing.system.identity.application.port.out;

/**
 * Port for password hashing. Implemented by {@code BcryptPasswordHasher}
 * (lecture 2's recommended BCrypt approach). Used by {@code AuthenticationService}, by governance's
 * {@code SystemAdminService} when it creates the default admin, and by the boot-time admin password
 * rotation.
 */
public interface PasswordHasher {

    /**
     * Hashes a raw password for storage. UC-11.
     *
     * <p>Every call produces a different string for the same password, because each carries a fresh
     * salt. A password longer than 72 bytes in UTF-8 is more than BCrypt reads;
     * {@code AuthenticationService} refuses one at registration.
     *
     * @param rawPassword the password as the user typed it
     * @return the hash to store, with its salt embedded
     */
    String hash(String rawPassword);

    /**
     * Verifies a raw password against a stored hash. UC-12.
     *
     * @param rawPassword the candidate password
     * @param storedHash  a hash made by {@link #hash}
     * @return {@code true} only if {@code rawPassword} produced {@code storedHash}
     */
    boolean matches(String rawPassword, String storedHash);
}
