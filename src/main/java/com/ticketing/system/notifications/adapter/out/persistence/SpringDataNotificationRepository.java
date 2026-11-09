package com.ticketing.system.notifications.adapter.out.persistence;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.ticketing.system.notifications.domain.Notification;
import com.ticketing.system.notifications.domain.NotificationStatus;

/**
 * Spring Data JPA repository for {@link Notification} — the auto-implemented SQL backing
 * {@link JpaNotificationRepository}. The application layer never sees this type; it depends
 * only on the {@code NotificationRepository} port. The {@code data} payload is mapped
 * by {@code NotificationDataJsonConverter}, so it round-trips as a JSON text column.
 */
public interface SpringDataNotificationRepository extends JpaRepository<Notification, String> {

    /** A derived query: the member's notifications in the given status, in no particular order. */
    List<Notification> findByRecipientUserIdAndStatus(int recipientUserId, NotificationStatus status);

    /** A derived query: all of the member's notifications, in no particular order. */
    List<Notification> findByRecipientUserId(int recipientUserId);
}
