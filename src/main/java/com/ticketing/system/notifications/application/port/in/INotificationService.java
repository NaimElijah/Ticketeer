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

    /**
     * Tells a member they hold a pending manager appointment in a company. Their role changes only once
     * they accept it, which sends its own role-change notification.
     *
     * @param userId      the invited member
     * @param companyId   the company the appointment is in
     * @param companyName the company's name, shown in the notification
     */
    void notifyManagerAppointmentPending(int userId, int companyId, String companyName);

    void notifyRoleChanged(int userId, int companyId, String companyName, String newRole);

    /**
     * Messaging → notification bridge. Fired when a new message/inquiry arrives for a
     * recipient (member or company owner) so they get a real-time DIRECT_MESSAGE notification.
     * {@code conversationId} lets the UI deep-link to the thread; {@code senderLabel} is a
     * human-readable origin (e.g. "a system admin", "a production company").
     */
    void notifyNewMessage(int recipientUserId, String conversationId, String senderLabel,
            String subject, String snippet);

    /**
     * Raises one DIRECT_MESSAGE notification per recipient of an admin outreach, each reading as
     * {@link #notifyNewMessage} would for that recipient and conversation. Each is stored on its own
     * transaction, in the recipients' order (ADR-019). A store failure stops the fan-out and propagates:
     * the notifications already stored stay, and the rest are not stored.
     *
     * @param recipients  every recipient and the conversation opened for them, at least one
     * @param senderLabel human-readable origin, e.g. "a system admin"
     * @param subject     the conversations' shared subject; null reads as empty
     * @param snippet     a short preview of the message body; null reads as empty
     * @throws IllegalArgumentException if {@code recipients} is null or empty; nothing is stored
     */
    void notifyOutreach(List<OutreachRecipient> recipients, String senderLabel, String subject,
            String snippet);

    /**
     * Mark one of the member's notifications READ, from SENT or from PENDING: a session ending in
     * another tab sends SENT notifications back to PENDING while this tab's bell still shows them.
     * Nothing changes when the id is unknown, belongs to another member, or is already READ.
     *
     * @param userId         the member who clicked the notification
     * @param notificationId the notification's id
     * @throws org.springframework.dao.DataAccessException if the store fails to read or save it; the
     *         notification then stays as it was
     */
    void markRead(int userId, String notificationId);

    /**
     * Send all of the member's SENT notifications back to PENDING, so the next login or poll delivers
     * them again. Called when a session closes before the member read them.
     *
     * @param userId the member whose session closed
     * @throws org.springframework.dao.DataAccessException if the store fails; the whole revert rolls
     *         back, so every one of those notifications stays SENT
     */
    void revertSentToPending(int userId);
}
