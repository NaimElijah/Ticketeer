package com.ticketing.system.notifications.application.port.out;

import com.ticketing.system.notifications.domain.Notification;

/**
 * Port for the low-level delivery channel (WebSocket, SSE, Email, etc.).
 * Responsible only for the technical act of pushing a notification to a recipient.
 */
public interface PushNotificationService {

    /**
     * Attempts to push a notification to a recipient.
     * @return true if the push was successful/acknowledged.
     */
    boolean send(int recipientUserId, Notification notification);

    /**
     * Checks if the delivery channel is currently capable of reaching the user.
     */
    boolean isReachable(int recipientUserId);
}
