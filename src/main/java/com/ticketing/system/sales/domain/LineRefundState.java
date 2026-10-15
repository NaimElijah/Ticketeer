package com.ticketing.system.sales.domain;

/**
 * Where one {@link ReceiptLine} stands in a refund (#20, ADR-001).
 *
 * <p>Legal transitions, all driven by the owning {@link OrderReceipt}:
 * <ul>
 *   <li>{@code NONE → PENDING}: a refund run claims the line before calling the payment gateway, so
 *       a concurrent run skips it.</li>
 *   <li>{@code PENDING → REFUNDED}: the gateway refunded the money and the run recorded it.</li>
 *   <li>{@code PENDING → NONE}: the gateway refused, so the claim is released for a later retry.</li>
 *   <li>{@code NONE → REFUNDED}: a whole-order refund that no other run has touched.</li>
 * </ul>
 */
public enum LineRefundState {
    /** Paid and not refunded. */
    NONE,
    /** Claimed by a refund that is in flight at the payment gateway. */
    PENDING,
    /** Refunded, and the refund is recorded on the receipt. */
    REFUNDED
}
