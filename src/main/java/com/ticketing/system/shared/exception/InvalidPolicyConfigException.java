package com.ticketing.system.shared.exception;

// Thrown when an Owner submits a malformed PurchasePolicy / DiscountPolicy.
// Examples: negative max-per-buyer, percentageOff > 100%, invalidFrom > validUntil.
// UC-21.
public class InvalidPolicyConfigException extends DomainException {

    public InvalidPolicyConfigException(String reason) {
        super("Invalid policy configuration: " + reason);
    }
}
