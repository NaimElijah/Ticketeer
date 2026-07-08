package com.ticketing.system.notifications.domain;

// Lifecycle states for a Notification (UC-36 design walkthrough).
//   PENDING  - stored offline, awaiting delivery to the bell
//   SENT     - shown in the bell, awaiting user interaction
//   READ     - user clicked the notification item in the UI
public enum NotificationStatus {
    PENDING,
    SENT,
    READ
}
