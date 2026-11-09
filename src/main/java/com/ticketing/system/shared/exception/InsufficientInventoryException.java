package com.ticketing.system.shared.exception;

/**
 * Thrown when a sale cannot be confirmed because the cart line does not match the zone's inventory
 * ({@code InventoryService.validateCanConfirmSale}): a seated line without seat numbers, a standing
 * line with seat numbers, a non-seated zone given seats, or fewer standing places reserved than the
 * line holds. UC-9, II.2.5 (selection mechanisms).
 *
 * <p>The three-argument constructor, whose message reads "requested n tickets, only m available", has
 * no caller. Checkout reports this type to the buyer as a sold-out outcome carrying the message,
 * which reaches the user.
 */
public class InsufficientInventoryException extends DomainException {

    public InsufficientInventoryException(String zoneId, int requested, int available) {
        super("Zone " + zoneId + ": requested " + requested + " tickets, only " + available + " available");
    }

    public InsufficientInventoryException(String message) {
        super(message);
    }
}
