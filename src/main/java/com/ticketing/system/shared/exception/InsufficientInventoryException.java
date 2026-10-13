package com.ticketing.system.shared.exception;

// Thrown when a reservation requests more tickets than the zone has available.
// UC-9, II.2.5 (selection mechanisms).
public class InsufficientInventoryException extends DomainException {

    public InsufficientInventoryException(String zoneId, int requested, int available) {
        super("Zone " + zoneId + ": requested " + requested + " tickets, only " + available + " available");
    }

    public InsufficientInventoryException(String message) {
        super(message);
    }
}
