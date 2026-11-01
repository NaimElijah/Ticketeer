package com.ticketing.system.notifications.application.service;

import java.util.List;
import lombok.extern.slf4j.Slf4j;

import com.ticketing.system.shared.dto.NotificationDTO;
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
import org.springframework.dao.OptimisticLockingFailureException;
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
        // DEBUG: an outreach fan-out dispatches once per recipient, and storing an alert is not a state
        // transition anyone acts on (ADR-019).
        log.debug("Dispatching notification for userId={}, type={}",
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

    /**
     * Deliver the member's PENDING notifications (UC-37), at login and on every poll, and mark each one
     * delivered SENT.
     *
     * <p>Deliberately NOT part of a surrounding transaction: NOT_SUPPORTED suspends any caller's tx, so
     * each pending notification is delivered + persisted on its own repository transaction. One failed
     * push — or a mid-flush error — can't roll back the others. Login calls this only after its own
     * TransactionTemplate commits. Per-item by design (Eval 5 acceptance #1's documented exception).
     *
     * <p>Two callers can deliver the same row at once, such as a poll on one device and a login on
     * another. A save that loses that {@code @Version} race is skipped, logged at DEBUG: the other caller
     * delivered the row, so it is not returned here, and delivery goes on with the next row (#187).
     *
     * @param userId the member whose notifications are delivered
     * @return the notifications this call delivered, as shown to the member; empty when none
     * @throws org.springframework.dao.DataAccessException if the store fails other than by a lost race
     */
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
                try {
                    notificationRepository.save(notification);
                } catch (OptimisticLockingFailureException lostRace) {
                    // Handled: the other caller saved this row first and returns it itself.
                    log.debug("Notification id={} for userId={} was delivered by another caller first; skipped",
                            notification.getId(), userId);
                    continue;
                }
                deliveredNotifications.add(notification.toDTO());
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
