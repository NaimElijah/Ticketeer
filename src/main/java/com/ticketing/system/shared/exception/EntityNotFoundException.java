package com.ticketing.system.shared.exception;

// Generic "looked up by ID, not found" — used by repositories and any service doing by-ID lookup.
public class EntityNotFoundException extends DomainException {

    public EntityNotFoundException(String entityType, Object id) {
        super(entityType + " not found: " + id);
    }

    public EntityNotFoundException(String message) {
        super(message);
    }
}
