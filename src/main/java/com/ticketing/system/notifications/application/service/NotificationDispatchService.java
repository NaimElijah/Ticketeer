package com.ticketing.system.notifications.application.service;

import java.util.List;
import lombok.extern.slf4j.Slf4j;

import com.ticketing.system.Core.Application.dto.NotificationDTO;
import com.ticketing.system.notifications.application.port.out.PushNotificationService;
import com.ticketing.system.notifications.application.port.out.NotificationRepository;
import com.ticketing.system.notifications.domain.Notification;
import com.ticketing.system.notifications.domain.NotificationStatus;

// Owns the entire notification subsystem (UC-35, UC-36, UC-37).
// - UC-35: dispatch on domain event → check online → push live or hand off to UC-36
// - UC-36: persist as PENDING for offline recipients
// - UC-37: deliver pending on MemberLoggedIn event (PENDING → DELIVERED)
//
// The Domain-Events pattern was committed at UC-35 (see design_walkthrough_summary.md §6).
// Listener wiring (which domain events trigger dispatchFromEvent) lives in code, off-diagram.
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
public class NotificationDispatchService {

    private final NotificationRepository notificationRepository;
    private final PushNotificationService pushService; // low-level push channel (used by deliverPending)

    public NotificationDispatchService(
            NotificationRepository notificationRepository,
            PushNotificationService pushService) {
        this.notificationRepository = notificationRepository;
        this.pushService = pushService;
    }

    /**
     * Central dispatch point for all notifications.
     * Persists the notification as PENDING; the polling scheduler delivers it
     * to the bell on the next tick. Live WebSocket/SSE push (#225) will replace
     * the scheduler path when implemented.
     */
    @Transactional
    public void dispatch(Notification notification) {
        if (!notification.isPending()) {
            throw new IllegalArgumentException(
                    "dispatch() requires a PENDING notification, but got status: " + notification.getStatus());
        }
        log.info("Dispatching notification for userId={}, type={}",
                notification.getRecipientUserId(), notification.getType());
        notificationRepository.save(notification);
    }

    // UC-35: triggered from a domain event.
    public void dispatchFromEvent(Object domainEvent) {
        // This will eventually resolve the notification details from the event 
        // and call dispatch(Notification).
        throw new UnsupportedOperationException("UC-35: not implemented");
    }

    // UC-36: store an offline notification in PENDING state.
    // Deprecated: logic now moved into dispatch()
    @Deprecated
    public void storePending(Notification notification) {
        dispatch(notification);
    }

    // UC-37: triggered by MemberLoggedIn event.
    // Deliberately NOT part of a surrounding transaction: NOT_SUPPORTED suspends the caller's tx
    // (e.g. the @Transactional login), so each pending notification is delivered + persisted on its
    // own repository transaction. One failed push — or a mid-flush error — can't roll back the others
    // or the login itself. Per-item by design (Eval 5 acceptance #1's documented exception).
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public List<NotificationDTO> deliverPending(int userId) {
        log.info("Delivering pending notifications for userId={}", userId);

        List<Notification> pendingNotifications = notificationRepository.findByRecipientAndStatus(userId,
                NotificationStatus.PENDING);
        List<NotificationDTO> deliveredNotifications = new java.util.ArrayList<>();

        for (Notification notification : pendingNotifications) {
            boolean pushSuccess = pushService.send(userId, notification);
            if (pushSuccess) {
                notification.markSent();
                notificationRepository.save(notification); // persist the status change
                deliveredNotifications.add(notification.toDTO()); // convert to DTO for return
            } else {
                log.error(
                        "Failed to push notification id={} to userId={}. Will remain pending for next login attempt.",
                        notification.getId(), userId);
            }
        }
        log.info("Delivered {} pending notifications to userId={}", deliveredNotifications.size(), userId);
        return deliveredNotifications;
    }
}
