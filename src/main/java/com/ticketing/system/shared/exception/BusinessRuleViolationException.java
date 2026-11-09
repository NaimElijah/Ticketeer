package com.ticketing.system.shared.exception;

/**
 * Catch-all for a broken business rule that has no more specific exception, such as deleting an
 * event with sales history, an outreach that matches no recipients or user text over the length limit
 * ({@code TextLimits}). Prefer a specific subclass when one fits; this is for the long tail. The
 * message reaches the user.
 */
public class BusinessRuleViolationException extends DomainException {

    /**
     * Creates the exception.
     *
     * @param message the rule that was broken, written for the user to read
     */
    public BusinessRuleViolationException(String message) {
        super(message);
    }
}
