package com.ticketing.system.notifications.application.port.out;
import com.ticketing.system.notifications.domain.NotificationStatus;
import com.ticketing.system.notifications.domain.Notification;

import java.util.List;

import com.ticketing.system.shared.IRepository;

/**
 * Port for storing and reading {@link Notification}s (UC-36, UC-37). The aggregate root has its own
 * repository, apart from the {@code User}'s, so that high-volume PENDING storage and delivery don't
 * load the user. Two adapters implement it, chosen by profile (ADR-005): a JPA one and an in-memory
 * one for the in-memory test suite.
 */
public interface NotificationRepository extends IRepository<Notification, String> {

    /**
     * Stores the notification: a new one is inserted and a loaded one is updated in place.
     *
     * @param notification the notification to store
     * @throws org.springframework.dao.OptimisticLockingFailureException on the JPA adapter, if another
     *         save of the same notification won first (a lost {@code @Version} race); within a
     *         caller's transaction the conflict appears when that transaction commits. The in-memory
     *         adapter never throws it
     * @throws org.springframework.transaction.TransactionException on the JPA adapter, if the
     *         transaction the save opens (none within a caller's) gets no connection
     *         ({@code CannotCreateTransactionException}) or fails to commit
     *         ({@code TransactionSystemException})
     * @throws org.springframework.dao.DataAccessException if the store fails
     */
    void save(Notification notification);

    /**
     * Finds a notification by its id.
     *
     * @param notificationId the notification's id; null is accepted
     * @return the notification, or null when the id is null or unknown
     */
    Notification findById(String notificationId);

    /**
     * Finds a member's notifications in one status. {@code NotificationDispatchService.deliverPending}
     * reads the PENDING ones with it, at login and on every poll (UC-37), and
     * {@code NotificationService.revertSentToPending} reads the SENT ones.
     *
     * @param recipientUserId the member the notifications are for
     * @param status          the status to match
     * @return the matching notifications in no particular order; empty when there are none
     */
    List<Notification> findByRecipientAndStatus(int recipientUserId, NotificationStatus status);

    /**
     * Finds every notification of a member, whatever its status.
     *
     * @param recipientUserId the member the notifications are for
     * @return the notifications in no particular order; empty when there are none
     */
    List<Notification> findByRecipient(int recipientUserId);
}
