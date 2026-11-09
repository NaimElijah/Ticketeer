package com.ticketing.system.shared.exception;

/**
 * Reserved for a guest checkout without the required contact details
 * ({@code GuestCheckoutContactDTO}: email and name). D5 (reversed): guests may check out, but only
 * with valid contact info.
 *
 * <p>No production code throws it today: {@code CheckoutService.validateGuestIdentityPresent}
 * refuses a missing guest session id or email with an {@link InvalidTokenException}.
 */
public class GuestCheckoutMissingContactException extends DomainException {

    /**
     * Creates the exception.
     *
     * @param reason the whole message, as the user may read it
     */
    public GuestCheckoutMissingContactException(String reason) {
        super(reason);
    }
}
