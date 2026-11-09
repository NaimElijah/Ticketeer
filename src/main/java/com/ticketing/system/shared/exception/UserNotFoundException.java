package com.ticketing.system.shared.exception;

/**
 * The {@link EntityNotFoundException} for a user lookup. UC-12, UC-13, UC-16, UC-23/24, UC-31, etc.
 */
public class UserNotFoundException extends EntityNotFoundException {

    /**
     * Creates the exception with an id-free message, for user-facing paths, so the id does not leak to
     * the UI (avoids exposing internal identifiers and enabling enumeration). A throw site that wants the
     * id in the server log writes it there itself.
     */
    public UserNotFoundException() {
        super("User not found");
    }

    /**
     * Creates the exception for a user id that matched no user.
     *
     * @param userId the id that was looked up
     */
    public UserNotFoundException(int userId) {
        super("User", userId);
    }
}
