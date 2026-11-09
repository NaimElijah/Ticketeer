package com.ticketing.system.shared.exception;

/**
 * Thrown when a purchase violates the effective purchase policy (company policy AND event policy), as
 * in a per-purchase ticket maximum exceeded or an age limit not met ({@code Event.validateEffectivePolicy}).
 * UC-9 (reservation policy check), UC-10 (checkout policy validation per II.2.8.1).
 *
 * <p>Its message is the policy's own refusal text for the buyer, naming only the rules the purchase
 * broke. The buyer reads it when a reservation is refused; checkout reports it as a policy rejection
 * with fixed text. Nothing throws it for a
 * discount policy.
 */
public class PolicyViolationException extends DomainException {

    /**
     * Creates the exception for a named policy.
     *
     * @param policyName the policy that was violated
     * @param reason why the purchase violates it
     */
    public PolicyViolationException(String policyName, String reason) {
        super("Policy '" + policyName + "' violated: " + reason);
    }

    /**
     * Creates the exception with a specific text.
     *
     * @param message the refusal text the buyer reads
     */
    public PolicyViolationException(String message) {
        super(message);
    }
}
