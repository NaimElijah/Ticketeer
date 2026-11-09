package com.ticketing.system.shared.exception;

/**
 * Thrown when a state machine rejects a requested transition. Thrown by {@code Ticket},
 * {@code Event}, {@code ActiveOrder} (checking out an empty order or one whose hold has run out) and
 * {@code Conversation}, and by {@code EventManagementService}, {@code SystemAdminService} (closing a
 * market that is not open) and {@code CheckoutService} for the same kind of refusal.
 *
 * <p>The three-argument form builds "X cannot transition from A to B". The message reaches the user.
 */
public class InvalidStateTransitionException extends DomainException {

    public InvalidStateTransitionException(String entityType, String fromState, String toState) {
        super(entityType + " cannot transition from " + fromState + " to " + toState);
    }

    public InvalidStateTransitionException(String message) {
        super(message);
    }
}
