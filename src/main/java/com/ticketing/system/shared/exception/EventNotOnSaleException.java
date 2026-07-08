package com.ticketing.system.shared.exception;

// Thrown when reservation/checkout is attempted on an event whose status is not ON_SALE.
// (DRAFT, SCHEDULED-but-not-yet-published, SOLD_OUT, CANCELED, COMPLETED → all reject.)
// UC-9, UC-10.
public class EventNotOnSaleException extends DomainException {

    public EventNotOnSaleException(Object eventId, String currentStatus) {
        super("Event " + eventId + " is not on sale (current status: " + currentStatus + ")");
    }
}
