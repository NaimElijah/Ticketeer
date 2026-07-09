package com.ticketing.system.notifications.adapter.in.event;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.ticketing.system.notifications.application.port.in.INotificationService;
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
 * Listeners are PLAIN synchronous {@link EventListener}s (not {@code @Async} /
 * {@code @TransactionalEventListener}): each fires inline within the publisher's thread and
 * transaction, preserving the exact timing of the former direct facade calls.
 */
@Component
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
    @EventListener
    public void on(PurchaseCompletedNotice notice) {
        // Delegate 1:1 to the former facade call, preserving arguments.
        notificationService.notifyPurchaseCompleted(notice.userId(), notice.totalPrice(), notice.ticketIds());
    }

    /** Raises a purchase-failed notification when a checkout attempt fails. */
    @EventListener
    public void on(PurchaseFailedNotice notice) {
        notificationService.notifyPurchaseFailed(notice.userId(), notice.reason());
    }

    /** Raises a reservation-success notification when a member reserves tickets. */
    @EventListener
    public void on(TicketReservationSucceededNotice notice) {
        notificationService.notifyTicketReservationSuccess(
                notice.userId(), notice.eventId(), notice.zoneId(), notice.quantity());
    }

    /** Raises a reservation-failure notification when a reservation attempt fails. */
    @EventListener
    public void on(TicketReservationFailedNotice notice) {
        notificationService.notifyTicketReservationFailure(
                notice.userId(), notice.eventId(), notice.zoneId(), notice.reason());
    }

    /** Raises a removal-success notification when a member removes a reservation. */
    @EventListener
    public void on(ReservationRemovalSucceededNotice notice) {
        notificationService.notifyRemoveTicketReservationSuccess(
                notice.userId(), notice.eventId(), notice.zoneId(), notice.quantity());
    }

    /** Raises a removal-failure notification when a removal attempt fails. */
    @EventListener
    public void on(ReservationRemovalFailedNotice notice) {
        notificationService.notifyRemoveTicketReservationFailure(
                notice.userId(), notice.eventId(), notice.zoneId(), notice.reason());
    }

    /** Raises an event-cancelled notification for a ticket holder. */
    @EventListener
    public void on(EventCancelledNotice notice) {
        notificationService.notifyEventCancelled(notice.userId(), notice.eventId(), notice.eventName());
    }

    /** Raises a manager-revoked notification when an appointment is revoked. */
    @EventListener
    public void on(ManagerRevokedNotice notice) {
        notificationService.notifyManagerRevoked(notice.userId(), notice.companyId(), notice.companyName());
    }

    /** Raises a pending-owner-appointment notification when a member is offered ownership. */
    @EventListener
    public void on(OwnerAppointmentPendingNotice notice) {
        notificationService.notifyOwnerAppointmentPending(
                notice.userId(), notice.companyId(), notice.companyName());
    }

    /** Raises a role-changed notification when a member's company role changes. */
    @EventListener
    public void on(RoleChangedNotice notice) {
        notificationService.notifyRoleChanged(
                notice.userId(), notice.companyId(), notice.companyName(), notice.newRole());
    }

    /** Raises a direct-message notification when a new message arrives for a recipient. */
    @EventListener
    public void on(NewMessageNotice notice) {
        notificationService.notifyNewMessage(
                notice.recipientUserId(), notice.conversationId(), notice.senderLabel(),
                notice.subject(), notice.snippet());
    }
}
