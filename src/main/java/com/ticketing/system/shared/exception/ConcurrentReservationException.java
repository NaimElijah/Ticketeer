package com.ticketing.system.shared.exception;

// Thrown when optimistic locking detects a concurrent modification on a Ticket / aggregate.
// Service layer should catch and retry per lecture 2's optimistic-locking pattern.
public class ConcurrentReservationException extends DomainException {

    public ConcurrentReservationException(Object aggregateId) {
        super("Concurrent modification detected on aggregate: " + aggregateId);
    }

    public ConcurrentReservationException(String message, Throwable cause) {
        super(message, cause);
    }
}
