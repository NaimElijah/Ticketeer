package com.ticketing.system.notifications.application.port.out;
import com.ticketing.system.notifications.domain.NotificationStatus;
import com.ticketing.system.notifications.domain.Notification;

import java.util.List;

import com.ticketing.system.shared.IRepository;

// Aggregate-root entry point for the Notification aggregate (UC-36 design walkthrough).
// Notification was promoted from a sub-entity of User to its own aggregate so high-volume
// PENDING-storage and login-time delivery (UC-37) don't drag the User aggregate.
public interface NotificationRepository extends IRepository<Notification, String> {

    void save(Notification notification);

    Notification findById(String notificationId);

    // Used by UC-37 (NotificationDispatchService.deliverPending) at login and on every poll, and by
    // NotificationService.revertSentToPending to find the SENT ones.
    List<Notification> findByRecipientAndStatus(int recipientUserId, NotificationStatus status);

    // Every notification of the recipient, whatever its status.
    List<Notification> findByRecipient(int recipientUserId);
}
