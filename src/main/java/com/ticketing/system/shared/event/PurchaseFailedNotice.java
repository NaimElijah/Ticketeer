package com.ticketing.system.shared.event;

/**
 * Integration event published by the sales context (CheckoutService) when a buyer's checkout
 * fails after being attempted. The notifications context listens for it and raises a
 * purchase-failed notification. Mirrors the former
 * {@code INotificationService.notifyPurchaseFailed} call.
 */
public record PurchaseFailedNotice(
        int userId,     // the member whose checkout failed
        String reason   // human-readable failure reason to surface to the member
) {
}
