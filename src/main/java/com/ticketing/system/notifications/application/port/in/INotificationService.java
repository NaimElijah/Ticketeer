package com.ticketing.system.notifications.application.port.in;

import java.util.List;

// High-level, application-facing notification facade. Each notifyXxx method translates a
// business event into a Notification domain object and delegates to NotificationDispatchService,
// which persists it and routes delivery. The low-level push channel (WebSocket / SSE / email)
// is a separate port, PushNotificationService.
// V1 implementation is NotificationService.
public interface INotificationService {

    void notifyPurchaseCompleted(int userId, double totalPrice, List<Integer> list);

    void notifyPurchaseFailed(int userId, String string);

    void notifyTicketReservationSuccess(int userId, int eventId, int zoneId, int quantity);

    void notifyTicketReservationFailure(int userId, int eventId, int zoneId, String reason);

    void notifyRemoveTicketReservationFailure(int userId, int eventId, int zoneId, String string);

    void notifyRemoveTicketReservationSuccess(int userId, int eventId, int zoneId, int i);

    void notifyEventCancelled(int userId, int eventId, String eventName);

    void notifyManagerRevoked(int userId, int companyId, String companyName);

    void notifyOwnerAppointmentPending(int userId, int companyId, String companyName);

    void notifyRoleChanged(int userId, int companyId, String companyName, String newRole);

    /**
     * Messaging → notification bridge. Fired when a new message/inquiry arrives for a
     * recipient (member or company owner) so they get a real-time DIRECT_MESSAGE notification.
     * {@code conversationId} lets the UI deep-link to the thread; {@code senderLabel} is a
     * human-readable origin (e.g. "a system admin", "a production company").
     */
    void notifyNewMessage(int recipientUserId, String conversationId, String senderLabel,
            String subject, String snippet);

    /** Mark a single notification as READ (SENT → READ). No-op if already READ. */
    void markRead(int userId, String notificationId);

    /** Revert all SENT notifications for a user back to PENDING — called on disconnect. */
    void revertSentToPending(int userId);
}
