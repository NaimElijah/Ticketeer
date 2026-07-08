package com.ticketing.system.notifications.adapter.out.persistence;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.ticketing.system.notifications.application.port.out.NotificationRepository;
import com.ticketing.system.notifications.domain.Notification;
import com.ticketing.system.notifications.domain.NotificationStatus;

/**
 * JPA-backed {@link NotificationRepository} — active only in the {@code jpa} run/dev
 * profile. Adapts the domain port onto Spring Data ({@link SpringDataNotificationRepository});
 * the application layer depends only on {@code NotificationRepository}, never on Spring Data.
 *
 * <p>{@code lockForUpdate}/{@code unlock} are no-ops: concurrent writes are guarded by
 * {@code Notification}'s {@code @Version} optimistic lock within the surrounding transaction.
 * {@link #save} delegates to {@code data.save}: a fresh notification (version {@code null}) is
 * inserted, a loaded-then-mutated one (markDelivered / markPending) is updated under its
 * {@code @Version} check. The write is {@code @Transactional} so the adapter is self-sufficient
 * before the service layer gains transactions (#359); reads inherit Spring Data's read-only tx.
 */
@Repository
@Profile("jpa")
public class JpaNotificationRepository implements NotificationRepository {

    private final SpringDataNotificationRepository data;
    private final AtomicInteger idSequence = new AtomicInteger(1);

    public JpaNotificationRepository(SpringDataNotificationRepository data) {
        this.data = data;
    }

    @Override
    public void lockForUpdate(String id) { /* no-op — @Version optimistic locking */ }

    @Override
    public void unlock(String id) { /* no-op */ }

    @Override
    @Transactional
    public void save(Notification notification) {
        data.save(notification);
    }

    @Override
    public Notification findById(String notificationId) {
        if (notificationId == null) {
            return null;
        }
        return data.findById(notificationId).orElse(null);
    }

    @Override
    public List<Notification> findByRecipientAndStatus(int recipientUserId, NotificationStatus status) {
        return data.findByRecipientUserIdAndStatus(recipientUserId, status);
    }

    @Override
    public List<Notification> findByRecipient(int recipientUserId) {
        return data.findByRecipientUserId(recipientUserId);
    }

    @Override
    public int nextId() {
        // Vestigial: no caller uses notification nextId() (ids are UUIDs). Kept only to
        // satisfy the port; an in-memory counter is enough since the value is never persisted.
        return idSequence.getAndIncrement();
    }
}
