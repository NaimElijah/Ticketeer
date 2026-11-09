package com.ticketing.system.shared.exception;

/**
 * Thrown when a state machine rejects a requested transition. Thrown by {@code Ticket},
 * {@code Event}, {@code ActiveOrder} (checking out an empty order or one whose hold has run out) and
 * {@code Conversation}, and by {@code EventManagementService}, {@code SystemAdminService} (closing a
 * market that is not open) and {@code CheckoutService} for the same kind of refusal.
 *
 * <p>The three-argument form builds "X cannot transition from A to B". The message reaches the user,
 * except that {@code CheckoutPresenter} replaces it with fixed text, other than the empty-order
 * message, which it matches by its text ({@code EMPTY_ORDER_MESSAGE}).
 */
public class InvalidStateTransitionException extends DomainException {

    /**
     * Creates the exception for a refused transition.
     *
     * @param entityType the kind of entity, for example {@code "Event"}
     * @param fromState the name of the current state
     * @param toState the name of the state that was requested
     */
    public InvalidStateTransitionException(String entityType, String fromState, String toState) {
        super(entityType + " cannot transition from " + fromState + " to " + toState);
    }

    /**
     * Creates the exception with a specific text.
     *
     * @param message the text the user reads
     */
    public InvalidStateTransitionException(String message) {
        super(message);
    }
}
