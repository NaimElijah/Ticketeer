package com.ticketing.system.notifications.application.port.out;

import com.ticketing.system.notifications.domain.Notification;

/**
 * Port for the low-level delivery channel (WebSocket, SSE, Email, etc.).
 * Responsible only for the technical act of pushing a notification to a recipient.
 */
public interface PushNotificationService {

    /**
     * Attempts to push a notification to a recipient.
     * {@code NotificationDispatchService.deliverPending} calls it for each PENDING notification: on
     * {@code true} it marks the notification SENT, and on {@code false} it logs an error and leaves it
     * PENDING for a later delivery. It does not catch an exception from this method.
     *
     * @param recipientUserId the member to push to
     * @param notification    the notification to push
     * @return true if the push was successful/acknowledged.
     */
    boolean send(int recipientUserId, Notification notification);

    /**
     * Checks if the delivery channel is currently capable of reaching the user.
     * {@code NotificationDispatchService.deliverPending} does not consult it.
     *
     * @param recipientUserId the member to check
     * @return true if a push to the member can be attempted now
     */
    boolean isReachable(int recipientUserId);
}
