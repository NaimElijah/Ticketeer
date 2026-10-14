package com.ticketing.system.notifications.domain;

// Categorizes Notification entities (UC-35 design walkthrough).
// Which types must be sent (UC-35, I.5) is OQ-4 in docs/requirements/requirements.md; see #63.
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
    MANAGER_REVOKED,

    // General member (I.5.3)
    DIRECT_MESSAGE, PURCHASE_FAILED, TICKET_RESERVATION_SUCCESS, TICKET_RESERVATION_FAILURE, REMOVE_TICKET_RESERVATION_FAILURE, REMOVE_TICKET_RESERVATION_SUCCESS
}
