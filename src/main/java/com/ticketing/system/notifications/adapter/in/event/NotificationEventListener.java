package com.ticketing.system.notifications.adapter.in.event;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import com.ticketing.system.notifications.application.port.in.INotificationService;
import com.ticketing.system.notifications.config.NotificationAsyncConfig;
import com.ticketing.system.shared.event.EventCancelledNotice;
import com.ticketing.system.shared.event.ManagerRevokedNotice;
import com.ticketing.system.shared.event.NewMessageNotice;
import com.ticketing.system.shared.event.OwnerAppointmentPendingNotice;
import com.ticketing.system.shared.event.PurchaseCompletedNotice;
import com.ticketing.system.shared.event.PurchaseFailedNotice;
import com.ticketing.system.shared.event.ReservationRemovalFailedNotice;
import com.ticketing.system.shared.event.ReservationRemovalSucceededNotice;
import com.ticketing.system.shared.event.RoleChangedNotice;
import com.ticketing.system.shared.event.TicketReservationFailedNotice;
import com.ticketing.system.shared.event.TicketReservationSucceededNotice;

/**
 * Inbound adapter that turns cross-context integration events (the {@code *Notice} records in
 * {@code shared.event}) into calls on the notifications facade. This is the ONLY edge from the
 * source contexts (sales, catalog, organization, messaging) into notifications: they publish
 * shared-kernel events, this listener consumes them, so no source context imports notifications.
 *
 * <p>Every notice is consumed after the publisher's transaction commits, on the single
 * {@code notify-} thread (ADR-004). A notice from a transaction that rolls back is never delivered;
 * one published outside a transaction ({@code fallbackExecution}) is delivered at once, still off the
 * publisher's thread. A failure here therefore cannot reach the publisher: it is logged by the async
 * uncaught-exception handler. Any new consumer of a {@code *Notice} must follow the same pattern, or
 * it couples the publishers to notification delivery again.
 */
@Component
@Async(NotificationAsyncConfig.NOTIFICATION_EXECUTOR)
public class NotificationEventListener {

    // The existing notifications facade this adapter delegates to (unchanged application bean).
    private final INotificationService notificationService;

    /**
     * Wires the listener to the notifications facade bean.
     *
     * @param notificationService the inbound notifications port to delegate every event to
     */
    public NotificationEventListener(INotificationService notificationService) {
        this.notificationService = notificationService; // store the facade for delegation
    }

    /** Raises a purchase-confirmed notification when a purchase completes. */
    @TransactionalEventListener(fallbackExecution = true)
    public void on(PurchaseCompletedNotice notice) {
        // Delegate 1:1 to the former facade call, preserving arguments.
        notificationService.notifyPurchaseCompleted(notice.userId(), notice.totalPrice(), notice.ticketIds());
    }

    /** Raises a purchase-failed notification when a checkout attempt fails. */
    @TransactionalEventListener(fallbackExecution = true)
    public void on(PurchaseFailedNotice notice) {
        notificationService.notifyPurchaseFailed(notice.userId(), notice.reason());
    }

    /** Raises a reservation-success notification when a member reserves tickets. */
    @TransactionalEventListener(fallbackExecution = true)
    public void on(TicketReservationSucceededNotice notice) {
        notificationService.notifyTicketReservationSuccess(
                notice.userId(), notice.eventId(), notice.zoneId(), notice.quantity());
    }

    /**
     * Raises a reservation-failure notification. Not delivered today: the notice is published inside
     * the reservation's transaction, which rolls back on failure (ADR-004, #63).
     */
    @TransactionalEventListener(fallbackExecution = true)
    public void on(TicketReservationFailedNotice notice) {
        notificationService.notifyTicketReservationFailure(
                notice.userId(), notice.eventId(), notice.zoneId(), notice.reason());
    }

    /** Raises a removal-success notification when a member removes a reservation. */
    @TransactionalEventListener(fallbackExecution = true)
    public void on(ReservationRemovalSucceededNotice notice) {
        notificationService.notifyRemoveTicketReservationSuccess(
                notice.userId(), notice.eventId(), notice.zoneId(), notice.quantity());
    }

    /**
     * Raises a removal-failure notification. Not delivered today: the notice is published inside the
     * removal's transaction, which rolls back on failure (ADR-004, #63).
     */
    @TransactionalEventListener(fallbackExecution = true)
    public void on(ReservationRemovalFailedNotice notice) {
        notificationService.notifyRemoveTicketReservationFailure(
                notice.userId(), notice.eventId(), notice.zoneId(), notice.reason());
    }

    /** Raises an event-cancelled notification for a ticket holder. */
    @TransactionalEventListener(fallbackExecution = true)
    public void on(EventCancelledNotice notice) {
        notificationService.notifyEventCancelled(notice.userId(), notice.eventId(), notice.eventName());
    }

    /** Raises a manager-revoked notification when an appointment is revoked. */
    @TransactionalEventListener(fallbackExecution = true)
    public void on(ManagerRevokedNotice notice) {
        notificationService.notifyManagerRevoked(notice.userId(), notice.companyId(), notice.companyName());
    }

    /** Raises a pending-owner-appointment notification when a member is offered ownership. */
    @TransactionalEventListener(fallbackExecution = true)
    public void on(OwnerAppointmentPendingNotice notice) {
        notificationService.notifyOwnerAppointmentPending(
                notice.userId(), notice.companyId(), notice.companyName());
    }

    /** Raises a role-changed notification when a member's company role changes. */
    @TransactionalEventListener(fallbackExecution = true)
    public void on(RoleChangedNotice notice) {
        notificationService.notifyRoleChanged(
                notice.userId(), notice.companyId(), notice.companyName(), notice.newRole());
    }

    /** Raises a direct-message notification when a new message arrives for a recipient. */
    @TransactionalEventListener(fallbackExecution = true)
    public void on(NewMessageNotice notice) {
        notificationService.notifyNewMessage(
                notice.recipientUserId(), notice.conversationId(), notice.senderLabel(),
                notice.subject(), notice.snippet());
    }
}
