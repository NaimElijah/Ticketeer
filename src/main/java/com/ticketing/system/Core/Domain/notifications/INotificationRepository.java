package com.ticketing.system.Core.Domain.notifications;
import com.ticketing.system.identity.domain.User;

import java.util.List;

import com.ticketing.system.shared.IRepository;

// Aggregate-root entry point for the Notification aggregate (UC-36 design walkthrough).
// Notification was promoted from a sub-entity of User to its own aggregate so high-volume
// PENDING-storage and login-time delivery (UC-37) don't drag the User aggregate.
public interface INotificationRepository extends IRepository<Notification, String> {

    void save(Notification notification);

    Notification findById(String notificationId);

    // Used by UC-37 (NotificationDispatchService.deliverPending) on successful login.
    List<Notification> findByRecipientAndStatus(int recipientUserId, NotificationStatus status);

    // Used by MemberAccountService for inbox view of a member's notification history.
    List<Notification> findByRecipient(int recipientUserId);

    int nextId();
}
