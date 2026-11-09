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
     * salt. {@code AuthenticationService} refuses a password too long to hash at registration.
     *
     * @param rawPassword the password as the user typed it
     * @return the hash to store, with its salt embedded
     * @throws IllegalArgumentException if {@code rawPassword} is {@code null} or longer than 72 bytes
     *     in UTF-8, the most BCrypt reads
     */
    String hash(String rawPassword);

    /**
     * Verifies a raw password against a stored hash. UC-12.
     *
     * @param rawPassword the candidate password
     * @param storedHash  a hash made by {@link #hash}
     * @return {@code true} if {@code rawPassword} matches {@code storedHash} under BCrypt's comparison;
     *     {@code false} otherwise, and when either argument is {@code null}
     */
    boolean matches(String rawPassword, String storedHash);
}
