package com.ticketing.system.shared.exception;

/**
 * Thrown when a lookup by id finds nothing, for example a receipt or an active order in the sales and
 * reporting services. It is also the base of the typed not-found exceptions for a company, an event,
 * a user, a conversation, a ticket and a venue map. The message reaches the user, except that the receipt and refund
 * presenters replace it with not-found text, so that another member's receipt looks like a missing one.
 */
public class EntityNotFoundException extends DomainException {

    /**
     * Creates the exception for an id that matched nothing.
     *
     * @param entityType a display name for the kind of entity, for example {@code "OrderReceipt"}
     * @param id the id that was looked up
     */
    public EntityNotFoundException(String entityType, Object id) {
        super(entityType + " not found: " + id);
    }

    /**
     * Creates the exception with a specific text.
     *
     * @param message the text the user reads
     */
    public EntityNotFoundException(String message) {
        super(message);
    }
}
