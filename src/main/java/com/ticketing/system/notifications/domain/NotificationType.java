package com.ticketing.system.notifications.domain;

/**
 * What a {@link Notification} is about (UC-35). The constants are grouped by the audience the
 * requirements name: buyers (I.5.2), producers (I.5.1) and any member (I.5.3). PURCHASE_FAILED and the
 * reservation constants sit in the general-member group, although I.5's lists do not name them.
 *
 * <p>Which types must be sent is OQ-4 in {@code docs/requirements/requirements.md}; see #63. The type
 * is stored by name, so a new constant needs a migration that re-adds {@code ck_notifications_type}
 * with it (ADR-007).
 */
public enum NotificationType {
    // Buyer-facing (I.5.2)
    PURCHASE_CONFIRMED,
    REFUND_ISSUED,
    EVENT_CANCELLED,
    CART_EXPIRING,

    // Producer-facing (I.5.1)
    EVENT_SOLD_OUT,
    COMPANY_CLOSED,
    ROLE_CHANGED,
    OWNER_APPOINTMENT_PENDING,
    MANAGER_APPOINTMENT_PENDING,
    MANAGER_REVOKED,

    // General member (I.5.3)
    DIRECT_MESSAGE, PURCHASE_FAILED, TICKET_RESERVATION_SUCCESS, TICKET_RESERVATION_FAILURE, REMOVE_TICKET_RESERVATION_FAILURE, REMOVE_TICKET_RESERVATION_SUCCESS
}
