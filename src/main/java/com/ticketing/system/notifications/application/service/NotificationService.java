package com.ticketing.system.notifications.application.service;
import com.ticketing.system.notifications.application.service.NotificationDispatchService;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ticketing.system.notifications.application.port.in.INotificationService;
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
// Each write op (notify*/markRead/revertSentToPending) runs inside one service-level transaction (Eval 5).
@Transactional
public class NotificationService implements INotificationService {

    private final NotificationDispatchService dispatcher;
    private final NotificationRepository notificationRepository;

    @Override
    public void notifyPurchaseCompleted(int userId, double totalPrice, List<Integer> ticketIds) {
        Notification notification = new Notification(
                UUID.randomUUID().toString(),
                userId,
                NotificationType.PURCHASE_CONFIRMED,
                NotificationStatus.PENDING,
                "Purchase completed successfully. Total price: " + totalPrice,
                LocalDateTime.now()
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
                LocalDateTime.now()
        );
        dispatcher.dispatch(notification);
    }

    @Override
    public void notifyTicketReservationSuccess(int userId, int eventId, int zoneId, int quantity) {
        String message = String.format("Ticket reservation completed successfully. eventId=%d, zoneId=%d, quantity=%d",
                eventId, zoneId, quantity);

        Notification notification = new Notification(
                UUID.randomUUID().toString(),
                userId,
                NotificationType.TICKET_RESERVATION_SUCCESS,
                NotificationStatus.PENDING,
                message,
                LocalDateTime.now()
        );
        dispatcher.dispatch(notification);
    }

    @Override
    public void notifyTicketReservationFailure(int userId, int eventId, int zoneId, String reason) {
        String message = String.format("Ticket reservation failed. eventId=%d, zoneId=%d, reason=%s",
                eventId, zoneId, reason);

        Notification notification = new Notification(
                UUID.randomUUID().toString(),
                userId,
                NotificationType.TICKET_RESERVATION_FAILURE,
                NotificationStatus.PENDING,
                message,
                LocalDateTime.now()
        );
        dispatcher.dispatch(notification);
    }

    @Override
    public void notifyRemoveTicketReservationFailure(int userId, int eventId, int zoneId, String reason) {
        String message = String.format("Remove Ticket reservation failed. eventId=%d, zoneId=%d, reason=%s",
                eventId, zoneId, reason);

        Notification notification = new Notification(
                UUID.randomUUID().toString(),
                userId,
                NotificationType.REMOVE_TICKET_RESERVATION_FAILURE,
                NotificationStatus.PENDING,
                message,
                LocalDateTime.now()
        );
        dispatcher.dispatch(notification);
    }

    @Override
    public void notifyRemoveTicketReservationSuccess(int userId, int eventId, int zoneId, int quantity) {
        String message = String.format("Remove Ticket reservation completed successfully. eventId=%d, zoneId=%d, quantity=%d",
                eventId, zoneId, quantity);

        Notification notification = new Notification(
                UUID.randomUUID().toString(),
                userId,
                NotificationType.REMOVE_TICKET_RESERVATION_SUCCESS,
                NotificationStatus.PENDING,
                message,
                LocalDateTime.now()
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
                LocalDateTime.now(),
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
                LocalDateTime.now(),
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
                LocalDateTime.now(),
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
                LocalDateTime.now(),
                Map.<String, Object>of("companyId", companyId, "companyName", companyName, "newRole", newRole)
        );
        dispatcher.dispatch(notification);
    }

    @Override
    public void notifyNewMessage(int recipientUserId, String conversationId, String senderLabel,
            String subject, String snippet) {
        String safeSubject = subject == null ? "" : subject;
        String safeSnippet = snippet == null ? "" : snippet;
        String message = String.format("New message from %s: %s", senderLabel, safeSubject);
        Notification notification = new Notification(
                UUID.randomUUID().toString(),
                recipientUserId,
                NotificationType.DIRECT_MESSAGE,
                NotificationStatus.PENDING,
                message,
                LocalDateTime.now(),
                Map.<String, Object>of(
                        "conversationId", conversationId,
                        "senderLabel", senderLabel,
                        "subject", safeSubject,
                        "snippet", safeSnippet)
        );
        dispatcher.dispatch(notification);
    }

    @Override
    public void markRead(int userId, String notificationId) {
        try {
            Notification n = notificationRepository.findById(notificationId);
            if (n == null || n.getRecipientUserId() != userId || n.isRead()) return;
            n.markRead();
            notificationRepository.save(n);
        } catch (Exception e) {
            log.warn("markRead failed for userId={} notifId={}: {}", userId, notificationId, e.getMessage());
        }
    }

    @Override
    public void revertSentToPending(int userId) {
        List<Notification> sent = notificationRepository.findByRecipientAndStatus(userId, NotificationStatus.SENT);
        for (Notification n : sent) {
            try {
                n.markPending();
                notificationRepository.save(n);
            } catch (Exception e) {
                log.warn("revertSentToPending failed for notifId={}: {}", n.getId(), e.getMessage());
            }
        }
        if (!sent.isEmpty()) {
            log.info("reverted {} SENT notification(s) to PENDING for userId={}", sent.size(), userId);
        }
    }
}
