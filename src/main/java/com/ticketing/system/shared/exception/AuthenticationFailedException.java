package com.ticketing.system.shared.exception;

/**
 * Thrown when a login's credentials do not match: member sign-in
 * ({@code AuthenticationService.login}) and admin sign-in, for an unknown name and a wrong password
 * alike. UC-12.
 *
 * <p>The message is always the same generic text, so it does not tell which user names exist. It
 * reaches the user as written. Checkout also throws it when a valid member token yields a
 * non-positive user id. {@link AccountLockedException} is the separate outcome for a locked account.
 */
public class AuthenticationFailedException extends DomainException {

    public AuthenticationFailedException() {
        super("Invalid credentials");
    }
}
