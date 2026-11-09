package com.ticketing.system.notifications.application.service;

import java.util.List;
import lombok.extern.slf4j.Slf4j;

import com.ticketing.system.shared.dto.NotificationDTO;
import com.ticketing.system.notifications.application.port.out.PushNotificationService;
import com.ticketing.system.notifications.application.port.out.NotificationRepository;
import com.ticketing.system.notifications.domain.Notification;
import com.ticketing.system.notifications.domain.NotificationStatus;

import org.springframework.dao.DataAccessException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stores notifications and delivers them to members (UC-35, UC-36, UC-37).
 *
 * <p>{@link #dispatch} stores every notification as PENDING (UC-36). {@link #deliverPending} later
 * pushes a member's PENDING notifications and marks them SENT (UC-37); identity's
 * {@code AuthenticationService} calls it at login, and bootstrap's {@code NotificationPollingScheduler}
 * calls it on every poll. Nothing is pushed at dispatch time, whether or not the recipient is signed in.
 * {@link #dispatchFromEvent}, UC-35's event-driven entry point, is not implemented: notices reach
 * notifications through {@code NotificationEventListener} and {@code NotificationService}.
 */
@Service
@Slf4j
public class NotificationDispatchService {

    private final NotificationRepository notificationRepository;
    private final PushNotificationService pushService; // low-level push channel (used by deliverPending)

    /**
     * Wires the service to the notification store and the push channel.
     *
     * @param notificationRepository where notifications are stored and read
     * @param pushService            the channel {@link #deliverPending} pushes through
     */
    public NotificationDispatchService(
            NotificationRepository notificationRepository,
            PushNotificationService pushService) {
        this.notificationRepository = notificationRepository;
        this.pushService = pushService;
    }

    /**
     * Central dispatch point for all notifications.
     * Persists the notification as PENDING; the polling scheduler delivers it
     * to the bell on the next tick, or a login does (see {@link #deliverPending}). Live WebSocket/SSE
     * push (#225) will replace the scheduler path when implemented.
     *
     * <p>Joins the caller's transaction when there is one, and otherwise stores the notification in its
     * own.
     *
     * @param notification the notification to store; its status must be PENDING
     * @throws IllegalArgumentException if the notification is not PENDING; nothing is stored
     * @throws org.springframework.dao.DataAccessException if the store fails
     * @throws TransactionException if the transaction this call opens (none when it joins its caller's)
     *         gets no connection ({@code CannotCreateTransactionException}) or fails to commit
     *         ({@code TransactionSystemException}); nothing is stored
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

    /**
     * UC-35: the entry point meant to turn a domain event into a notification. Not implemented; notices
     * reach notifications through {@code NotificationEventListener} instead.
     *
     * @param domainEvent the event that would trigger a notification
     * @throws UnsupportedOperationException always
     */
    public void dispatchFromEvent(Object domainEvent) {
        throw new UnsupportedOperationException("UC-35: not implemented");
    }

    /**
     * UC-36: stores a notification in PENDING state.
     *
     * @param notification the notification to store; its status must be PENDING
     * @throws IllegalArgumentException if the notification is not PENDING
     * @deprecated the logic moved into {@link #dispatch}; call that instead
     */
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
     * another, or a click can mark it read meanwhile. A save that loses that {@code @Version} race is
     * skipped, logged at DEBUG: the row was saved first by another delivery or a click, so it is not
     * returned here, and delivery goes on with the next row (#187).
     *
     * <p>Any other store failure on a save, a lost connection or a failed commit included, stops
     * delivery there and is logged at WARN. What was already saved as SENT is still returned, since it
     * is never PENDING again and would otherwise be shown nowhere; the rows not reached stay PENDING
     * for the next poll (#187).
     *
     * <p>Logs one INFO line per call, and only when it delivered something (#187).
     *
     * @param userId the member whose notifications are delivered
     * @return the notifications this call delivered, as shown to the member; empty when none
     * @throws DataAccessException if the store fails reading the pending notifications; the JPA
     *                             adapter reads with no transaction of its own, so a lost connection
     *                             surfaces here
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public List<NotificationDTO> deliverPending(int userId) {
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
                    // Handled: another delivery, which returns the row itself, or a click saved it first.
                    log.debug("Notification id={} for userId={} was saved first by another delivery or a click;"
                            + " skipped", notification.getId(), userId);
                    continue;
                } catch (DataAccessException | TransactionException storeFailure) {
                    // Handled by stopping: the next saves would most likely fail too, and the rows
                    // already saved as SENT are never PENDING again, so they must reach the caller.
                    log.warn("Delivery to userId={} stopped at notification id={} on a store failure; {} delivered,"
                            + " the rest stay PENDING", userId, notification.getId(),
                            deliveredNotifications.size(), storeFailure);
                    break;
                }
                deliveredNotifications.add(notification.toDTO());
            } else {
                log.error(
                        "Failed to push notification id={} to userId={}. Will remain pending for next login attempt.",
                        notification.getId(), userId);
            }
        }
        // INFO only when something changed state: the poller calls this for every signed-in tab each
        // minute, and most calls deliver nothing (#187).
        if (!deliveredNotifications.isEmpty()) {
            log.info("Delivered {} pending notifications to userId={}", deliveredNotifications.size(), userId);
        }
        return deliveredNotifications;
    }
}
