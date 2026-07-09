package com.ticketing.system.shared.event;

import java.util.List;

/**
 * Integration event published by the sales context (CheckoutService) when a buyer's checkout
 * completes successfully. The notifications context listens for it and raises a purchase-confirmed
 * notification. Carries exactly the arguments of the former
 * {@code INotificationService.notifyPurchaseCompleted} call so delivery is unchanged.
 */
public record PurchaseCompletedNotice(
        int userId,               // the member who completed the purchase
        double totalPrice,        // total amount charged for the order
        List<Integer> ticketIds   // ids of the tickets that were issued in this purchase
) {
}
