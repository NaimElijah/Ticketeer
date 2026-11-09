package com.ticketing.system.notifications.application.port.in;

import java.util.List;

/**
 * High-level, application-facing notification facade. Each {@code notifyXxx} method translates a
 * business event into a {@code Notification} domain object and delegates to
 * {@code NotificationDispatchService}, which stores it as PENDING; its {@code deliverPending} pushes it
 * later. The low-level push channel (WebSocket / SSE / email) is a separate port,
 * {@code PushNotificationService}. {@code markRead} and {@code revertSentToPending} move stored
 * notifications along their lifecycle. {@code NotificationService} implements it.
 *
 * <p>The name keeps its legacy prefix, because dropping it would collide with the implementation (see
 * the package documentation).
 *
 * <p>Each {@code notifyXxx} method fails with {@link IllegalStateException} for a non-positive
 * {@code userId} (or {@code recipientUserId}), since the {@code Notification} it builds refuses one.
 */
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

    /**
     * Tells a member their purchase failed.
     *
     * @param userId  the buyer
     * @param string why it failed; becomes the notification's text as given
     */
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

    /**
     * Tells a member an event they hold tickets for was cancelled. The text names the event and shows
     * no id; the id stays in the notification's data payload.
     *
     * @param userId    the ticket holder
     * @param eventId   the cancelled event
     * @param eventName the event's name, shown in the text; not null
     */
    void notifyEventCancelled(int userId, int eventId, String eventName);

    /**
     * Tells a member their manager role in a company was revoked.
     *
     * @param userId      the former manager
     * @param companyId   the company
     * @param companyName the company's name, shown in the text; not null
     */
    void notifyManagerRevoked(int userId, int companyId, String companyName);

    /**
     * Tells a member they hold a pending owner appointment in a company, which they must accept or
     * reject.
     *
     * @param userId      the invited member
     * @param companyId   the company the appointment is in
     * @param companyName the company's name, shown in the text; not null
     */
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

    /**
     * Tells a member their role in a company was updated.
     *
     * @param userId      the member whose role changed
     * @param companyId   the company
     * @param companyName the company's name, shown in the text; not null
     * @param newRole     the new role, shown in the text as given; not null
     */
    void notifyRoleChanged(int userId, int companyId, String companyName, String newRole);

    /**
     * Messaging → notification bridge. Fired when a new message/inquiry arrives for a
     * recipient (member or company owner) so they get a DIRECT_MESSAGE notification, stored PENDING
     * and shown on their next poll or login.
     * {@code conversationId} lets the UI deep-link to the thread; {@code senderLabel} is a
     * human-readable origin (e.g. "a system admin", "a production company").
     *
     * @param recipientUserId the member who receives the alert
     * @param conversationId  the conversation the message belongs to; not null
     * @param senderLabel     the origin shown in the text; not null
     * @param subject         the conversation's subject, shown in the text; null reads as empty
     * @param snippet         a short preview of the message body; null reads as empty
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
