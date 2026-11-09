package com.ticketing.system.shared.exception;

/**
 * Thrown when registration asks for a username that is already taken, by
 * {@code AuthenticationService.register} and by the user and admin repositories when a save finds
 * the username taken. UC-11.
 *
 * <p>{@link DuplicateEmailException} is the separate type for an email.
 */
public class DuplicateUsernameException extends DomainException {

    /**
     * Creates the exception.
     *
     * @param username the username that is already taken
     */
    public DuplicateUsernameException(String username) {
        super("Username already taken: " + username);
    }
}
