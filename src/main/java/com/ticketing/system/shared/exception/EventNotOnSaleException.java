package com.ticketing.system.shared.exception;

/**
 * Thrown when checkout finds an event that is neither {@code ON_SALE} nor {@code SOLD_OUT}
 * ({@code InventoryService.validateEventsOnSale}). UC-9, UC-10.
 *
 * <p>{@code SOLD_OUT} passes on purpose: a buyer whose tickets were reserved before the event sold
 * out may still complete the purchase. The message reaches the user.
 */
public class EventNotOnSaleException extends DomainException {

    public EventNotOnSaleException(Object eventId, String currentStatus) {
        super("Event " + eventId + " is not on sale (current status: " + currentStatus + ")");
    }
}
