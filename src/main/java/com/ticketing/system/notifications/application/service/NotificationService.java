package com.ticketing.system.notifications.application.service;
import com.ticketing.system.notifications.application.service.NotificationDispatchService;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.ticketing.system.notifications.application.port.in.INotificationService;
import com.ticketing.system.notifications.application.port.in.OutreachRecipient;
import com.ticketing.system.notifications.application.port.out.NotificationRepository;
import com.ticketing.system.notifications.domain.Notification;
import com.ticketing.system.notifications.domain.NotificationStatus;
import com.ticketing.system.notifications.domain.NotificationType;
import lombok.extern.slf4j.Slf4j;

import lombok.RequiredArgsConstructor;

/**
 * Implementation of INotificationService that translates business events
 * into Notification domain objects and hands them to the Dispatcher.
 */
@Service
@RequiredArgsConstructor
@Slf4j
// Each write op runs inside one service-level transaction (Eval 5), except notifyOutreach and
// revertSentToPending, which commit each row on its own.
@Transactional
public class NotificationService implements INotificationService {

    private final NotificationDispatchService dispatcher;
    private final NotificationRepository notificationRepository;
    // Stamps every notification: UTC wall time (ADR-008).
    private final Clock clock;

    @Override
    public void notifyPurchaseCompleted(int userId, double totalPrice, List<Integer> ticketIds) {
        Notification notification = new Notification(
                UUID.randomUUID().toString(),
                userId,
                NotificationType.PURCHASE_CONFIRMED,
                NotificationStatus.PENDING,
                // Locale.ROOT: the member reads the same total, with a point, whatever the JVM's locale.
                String.format(Locale.ROOT, "Purchase completed successfully. Total price: %.2f", totalPrice),
                LocalDateTime.now(clock)
        );
        dispatcher.dispatch(notification);
    }

    @Override
    public void notifyPurchaseFailed(int userId, String message) {
        Notification notification = new Notification(
                UUID.randomUUID().toString(),
                userId,
                NotificationType.PURCHASE_FAILED,
                NotificationStatus.PENDING,
                message,
                LocalDateTime.now(clock)
        );
        dispatcher.dispatch(notification);
    }

    @Override
    public void notifyTicketReservationSuccess(int userId, int eventId, int zoneId, int quantity) {
        String message = String.format("You reserved %s.", tickets(quantity));

        Notification notification = new Notification(
                UUID.randomUUID().toString(),
                userId,
                NotificationType.TICKET_RESERVATION_SUCCESS,
                NotificationStatus.PENDING,
                message,
                LocalDateTime.now(clock)
        );
        dispatcher.dispatch(notification);
    }

    @Override
    public void notifyTicketReservationFailure(int userId, int eventId, int zoneId, String reason) {
        String message = String.format("Your ticket reservation failed: %s", reason);

        Notification notification = new Notification(
                UUID.randomUUID().toString(),
                userId,
                NotificationType.TICKET_RESERVATION_FAILURE,
                NotificationStatus.PENDING,
                message,
                LocalDateTime.now(clock)
        );
        dispatcher.dispatch(notification);
    }

    @Override
    public void notifyRemoveTicketReservationFailure(int userId, int eventId, int zoneId, String reason) {
        String message = String.format("Removing your reserved tickets failed: %s", reason);

        Notification notification = new Notification(
                UUID.randomUUID().toString(),
                userId,
                NotificationType.REMOVE_TICKET_RESERVATION_FAILURE,
                NotificationStatus.PENDING,
                message,
                LocalDateTime.now(clock)
        );
        dispatcher.dispatch(notification);
    }

    @Override
    public void notifyRemoveTicketReservationSuccess(int userId, int eventId, int zoneId, int quantity) {
        String message = String.format("You removed %s from your reservation.", tickets(quantity));

        Notification notification = new Notification(
                UUID.randomUUID().toString(),
                userId,
                NotificationType.REMOVE_TICKET_RESERVATION_SUCCESS,
                NotificationStatus.PENDING,
                message,
                LocalDateTime.now(clock)
        );
        dispatcher.dispatch(notification);
    }

    @Override
    public void notifyEventCancelled(int userId, int eventId, String eventName) {
        String message = String.format("Event '%s' (ID: %d) has been cancelled. Refunds have been processed.", eventName, eventId);
        Notification notification = new Notification(
                UUID.randomUUID().toString(),
                userId,
                NotificationType.EVENT_CANCELLED,
                NotificationStatus.PENDING,
                message,
                LocalDateTime.now(clock),
                Map.<String, Object>of("eventId", eventId, "eventName", eventName)
        );
        dispatcher.dispatch(notification);
    }

    @Override
    public void notifyManagerRevoked(int userId, int companyId, String companyName) {
        String message = String.format("Your manager role in company '%s' has been revoked.", companyName);
        Notification notification = new Notification(
                UUID.randomUUID().toString(),
                userId,
                NotificationType.MANAGER_REVOKED,
                NotificationStatus.PENDING,
                message,
                LocalDateTime.now(clock),
                Map.<String, Object>of("companyId", companyId, "companyName", companyName)
        );
        dispatcher.dispatch(notification);
    }

    @Override
    public void notifyOwnerAppointmentPending(int userId, int companyId, String companyName) {
        String message = String.format("You have a pending owner appointment for company '%s'. Please accept or reject it.", companyName);
        Notification notification = new Notification(
                UUID.randomUUID().toString(),
                userId,
                NotificationType.OWNER_APPOINTMENT_PENDING,
                NotificationStatus.PENDING,
                message,
                LocalDateTime.now(clock),
                Map.<String, Object>of("companyId", companyId, "companyName", companyName)
        );
        dispatcher.dispatch(notification);
    }

    @Override
    public void notifyManagerAppointmentPending(int userId, int companyId, String companyName) {
        String message = String.format("You have a pending manager appointment for company '%s'. Please accept or reject it.", companyName);
        Notification notification = new Notification(
                UUID.randomUUID().toString(),
                userId,
                NotificationType.MANAGER_APPOINTMENT_PENDING,
                NotificationStatus.PENDING,
                message,
                LocalDateTime.now(clock),
                Map.<String, Object>of("companyId", companyId, "companyName", companyName)
        );
        dispatcher.dispatch(notification);
    }

    @Override
    public void notifyRoleChanged(int userId, int companyId, String companyName, String newRole) {
        String message = String.format("Your role in company '%s' has been updated to %s.", companyName, newRole);
        Notification notification = new Notification(
                UUID.randomUUID().toString(),
                userId,
                NotificationType.ROLE_CHANGED,
                NotificationStatus.PENDING,
                message,
                LocalDateTime.now(clock),
                Map.<String, Object>of("companyId", companyId, "companyName", companyName, "newRole", newRole)
        );
        dispatcher.dispatch(notification);
    }

    @Override
    public void notifyNewMessage(int recipientUserId, String conversationId, String senderLabel,
            String subject, String snippet) {
        dispatcher.dispatch(newMessageNotification(recipientUserId, conversationId, senderLabel, subject, snippet,
                LocalDateTime.now(clock)));
    }

    // NOT_SUPPORTED overrides the class's transaction, so each dispatch commits on its own through the
    // dispatcher's proxy: one transaction per alert, not one for the whole send (ADR-019).
    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void notifyOutreach(List<OutreachRecipient> recipients, String senderLabel, String subject,
            String snippet) {
        if (recipients == null || recipients.isEmpty()) {
            throw new IllegalArgumentException("An outreach needs at least one recipient");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        for (OutreachRecipient r : recipients) {
            dispatcher.dispatch(newMessageNotification(r.userId(), r.conversationId(), senderLabel,
                    subject, snippet, now));
        }
    }

    // The reservation texts name no event: that would need the event's name in their notices and a read
    // in sales' finally path, where a failing read would mask the reservation's own exception (#187).
    private static String tickets(int quantity) {
        return quantity + (quantity == 1 ? " ticket" : " tickets");
    }

    private static Notification newMessageNotification(int recipientUserId, String conversationId,
            String senderLabel, String subject, String snippet, LocalDateTime now) {
        String safeSubject = subject == null ? "" : subject;
        String safeSnippet = snippet == null ? "" : snippet;
        String message = String.format("New message from %s: %s", senderLabel, safeSubject);
        return new Notification(
                UUID.randomUUID().toString(),
                recipientUserId,
                NotificationType.DIRECT_MESSAGE,
                NotificationStatus.PENDING,
                message,
                now,
                Map.<String, Object>of(
                        "conversationId", conversationId,
                        "senderLabel", senderLabel,
                        "subject", safeSubject,
                        "snippet", safeSnippet)
        );
    }

    @Override
    public void markRead(int userId, String notificationId) {
        Notification n = notificationRepository.findById(notificationId);
        if (n == null || n.getRecipientUserId() != userId || n.isRead()) return;
        n.markRead();
        notificationRepository.save(n);
    }

    // NOT_SUPPORTED overrides the class's transaction, as deliverPending does: each row's save commits on
    // its own, so a failure part-way leaves the rows already sent back PENDING instead of rolling them all
    // back to SENT, where no later delivery would show them (#187).
    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void revertSentToPending(int userId) {
        List<Notification> sent = notificationRepository.findByRecipientAndStatus(userId, NotificationStatus.SENT);
        int sentBack = 0;
        for (Notification n : sent) {
            n.markPending();
            try {
                notificationRepository.save(n);
            } catch (OptimisticLockingFailureException lostRace) {
                // Handled: a click or another delivery saved this row first, so it is not SENT any more.
                log.debug("Notification id={} for userId={} was saved first by a click or a delivery; not sent back",
                        n.getId(), userId);
                continue;
            }
            sentBack++;
        }
        if (sentBack > 0) {
            log.info("reverted {} SENT notification(s) to PENDING for userId={}", sentBack, userId);
        }
    }
}
