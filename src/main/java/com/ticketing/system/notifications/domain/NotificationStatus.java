package com.ticketing.system.notifications.domain;

/**
 * Where a {@link Notification} is in its lifecycle (UC-36, UC-37). The status is stored by name, so a
 * new constant needs a migration that re-adds {@code ck_notifications_status} with it (ADR-007).
 */
public enum NotificationStatus {
    /** Stored and awaiting delivery to the bell, or sent back from {@link #SENT} when a session ended. */
    PENDING,
    /** Shown in the bell, awaiting user interaction. */
    SENT,
    /** The member clicked it. */
    READ
}
