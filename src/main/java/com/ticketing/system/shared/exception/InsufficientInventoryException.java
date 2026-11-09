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

    /**
     * Creates the exception for a request larger than what the zone holds.
     *
     * @param zoneId the zone
     * @param requested how many tickets were asked for
     * @param available how many tickets the zone can still give
     */
    public InsufficientInventoryException(String zoneId, int requested, int available) {
        super("Zone " + zoneId + ": requested " + requested + " tickets, only " + available + " available");
    }

    /**
     * Creates the exception with a specific text.
     *
     * @param message the text the user reads
     */
    public InsufficientInventoryException(String message) {
        super(message);
    }
}
