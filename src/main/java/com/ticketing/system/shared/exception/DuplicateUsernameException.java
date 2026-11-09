package com.ticketing.system.shared.exception;

/**
 * Thrown when registration asks for a username that is already taken, by
 * {@code AuthenticationService.register} and by the user and admin repositories when a save finds
 * the username taken. UC-11.
 *
 * <p>A taken email is {@link DuplicateEmailException}. The message reaches the user.
 */
public class DuplicateUsernameException extends DomainException {

    public DuplicateUsernameException(String username) {
        super("Username already taken: " + username);
    }
}
