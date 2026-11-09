package com.ticketing.system.shared.exception;

/**
 * Reserved for an owner's malformed purchase or discount policy, such as a negative per-buyer
 * maximum or a percentage over 100. UC-21.
 *
 * <p>No production code throws it today: a purchase policy with an out-of-range value fails its own
 * invariant check with an {@link IllegalStateException}.
 */
public class InvalidPolicyConfigException extends DomainException {

    /**
     * Creates the exception.
     *
     * @param reason what is wrong with the policy
     */
    public InvalidPolicyConfigException(String reason) {
        super("Invalid policy configuration: " + reason);
    }
}
