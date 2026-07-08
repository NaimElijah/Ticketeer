package com.ticketing.system.shared.exception;

// Thrown when a PurchasePolicy or DiscountPolicy invariant is violated.
// Examples: max-tickets-per-buyer exceeded, age-limit not met, discount code expired.
// UC-9 (reservation policy check), UC-10 (checkout policy validation per II.2.8.1).
public class PolicyViolationException extends DomainException {

    public PolicyViolationException(String policyName, String reason) {
        super("Policy '" + policyName + "' violated: " + reason);
    }

    public PolicyViolationException(String message) {
        super(message);
    }
}
