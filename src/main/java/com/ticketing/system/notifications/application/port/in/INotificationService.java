package com.ticketing.system.notifications.application.port.in;

import java.util.List;

// High-level, application-facing notification facade. Each notifyXxx method translates a
// business event into a Notification domain object and delegates to NotificationDispatchService,
// which persists it and routes delivery. The low-level push channel (WebSocket / SSE / email)
// is a separate port, PushNotificationService.
// V1 implementation is NotificationService.
public interface INotificationService {

    /**
     * Tells a member their purchase completed, with its total.
     *
     * @param userId     the buyer
     * @param totalPrice the amount paid; the text shows it with two decimals and a point, whatever
     *                   the JVM's locale
     * @param ticketIds  the tickets issued; not shown in the text
     */
    void notifyPurchaseCompleted(int userId, double totalPrice, List<Integer> ticketIds);

    void notifyPurchaseFailed(int userId, String string);

    /**
     * Tells a member how many tickets they reserved. The text names no event and shows no id (#187).
     *
     * @param userId   the member
     * @param eventId  the event; not shown in the text
     * @param zoneId   the zone; not shown in the text
     * @param quantity how many tickets were reserved
     */
    void notifyTicketReservationSuccess(int userId, int eventId, int zoneId, int quantity);

    /**
     * Tells a member their reservation failed, and why. The text names no event and shows no id (#187).
     *
     * @param userId  the member
     * @param eventId the event; not shown in the text
     * @param zoneId  the zone; not shown in the text
     * @param reason  why it failed, shown as given
     */
    void notifyTicketReservationFailure(int userId, int eventId, int zoneId, String reason);

    /**
     * Tells a member that removing reserved tickets failed, and why. The text names no event and shows
     * no id (#187).
     *
     * @param userId  the member
     * @param eventId the event; not shown in the text
     * @param zoneId  the zone; not shown in the text
     * @param reason  why it failed, shown as given
     */
    void notifyRemoveTicketReservationFailure(int userId, int eventId, int zoneId, String reason);

    /**
     * Tells a member how many reserved tickets they removed. The text names no event and shows no id
     * (#187).
     *
     * @param userId   the member
     * @param eventId  the event; not shown in the text
     * @param zoneId   the zone; not shown in the text
     * @param quantity how many tickets were removed
     */
    void notifyRemoveTicketReservationSuccess(int userId, int eventId, int zoneId, int quantity);

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
     * another browser or on another device sends SENT notifications back to PENDING while this
     * session's bell still shows them.
     * Nothing changes when the id is unknown, belongs to another member, or is already READ.
     *
     * @param userId         the member who clicked the notification
     * @param notificationId the notification's id
     * @throws org.springframework.dao.DataAccessException if the store fails to read or save it; the
     *         notification then stays as it was
     * @throws org.springframework.transaction.TransactionException if no connection can be had
     *         ({@code CannotCreateTransactionException}) or the save fails to commit
     *         ({@code TransactionSystemException}); the notification stays as it was
     * @throws com.ticketing.system.shared.exception.ConcurrentReservationException if a delivery or
     *         another click saved the notification first (a lost {@code @Version} race, re-typed by
     *         {@code OptimisticLockTranslationAspect}); it stays as that save left it
     */
    void markRead(int userId, String notificationId);

    /**
     * Send all of the member's SENT notifications back to PENDING, so the next login or poll delivers
     * them again. Called when a session closes before the member read them.
     *
     * <p>Each notification is sent back on its own transaction. One that a click or a delivery saved
     * first is skipped, since it is no longer SENT.
     *
     * @param userId the member whose session closed
     * @throws org.springframework.dao.DataAccessException if the store fails; the notifications already
     *         sent back stay PENDING, and the rest stay SENT
     * @throws org.springframework.transaction.TransactionException if a notification's save gets no
     *         connection ({@code CannotCreateTransactionException}) or fails to commit
     *         ({@code TransactionSystemException}); the notifications already sent back stay PENDING, and
     *         the rest stay SENT
     */
    void revertSentToPending(int userId);
}
