package com.ticketing.system.shared.exception;

/**
 * Thrown when registration asks for a username the platform reserves (UC-11, #76). A reserved name
 * is refused as a taken one, so every caller that handles {@link DuplicateUsernameException} refuses
 * it too; a caller that wants to say "reserved" catches this subtype first.
 */
public class ReservedUsernameException extends DuplicateUsernameException {

    /** @param username the requested username, as the caller sent it */
    public ReservedUsernameException(String username) {
        super(username);
    }
}
