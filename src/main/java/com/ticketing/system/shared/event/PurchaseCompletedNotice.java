package com.ticketing.system.shared.event;

import java.util.List;

/**
 * Integration event published by the sales context (CheckoutService) when a buyer's checkout
 * completes successfully. The notifications context listens for it and raises a purchase-confirmed
 * notification. Its components are the arguments of
 * {@code INotificationService.notifyPurchaseCompleted}, which the listener calls with them.
 *
 * @param userId the member who completed the purchase
 * @param totalPrice total price of the order
 * @param ticketIds ids of the tickets that were issued in this purchase
 */
public record PurchaseCompletedNotice(
        int userId,
        double totalPrice,
        List<Integer> ticketIds
) {
}
