package com.ticketing.system.notifications.adapter.out.persistence;

import com.ticketing.system.shared.persistence.RepositoryLocks;
import com.ticketing.system.notifications.application.port.out.NotificationRepository;
import com.ticketing.system.notifications.domain.Notification;
import com.ticketing.system.notifications.domain.NotificationStatus;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;


@Repository
@Profile("!jpa")
public class MemoryNotificationRepository implements NotificationRepository {
    // ConcurrentHashMap (not HashMap) for thread-safe concurrent access, matching the
    // other Memory repositories — notifications are written and read from many threads.
    private final Map<String, Notification> storage = new ConcurrentHashMap<>();
    private final AtomicInteger idSequence = new AtomicInteger(1);
    private final RepositoryLocks<String> locks = new RepositoryLocks<>();

    @Override
    public void lockForUpdate(String id) { locks.lock(id); }

    @Override
    public void unlock(String id) { locks.unlock(id); }

    @Override
    public int nextId() {
        return idSequence.getAndIncrement();
    }
    
    @Override
    public void save(Notification notification) {
        storage.put(notification.getId(), notification);
    }

    @Override
    public Notification findById(String id) {
        if (id == null) {
            return null;
        }
        return storage.get(id);
    }

    @Override
    public List<Notification> findByRecipientAndStatus(int recipientUserId, NotificationStatus status) {
        return storage.values().stream()
                .filter(n -> n.getRecipientUserId() == recipientUserId)
                .filter(n -> n.getStatus() == status)
                .collect(Collectors.toList());
    }

    @Override
    public List<Notification> findByRecipient(int recipientUserId) {
        return storage.values().stream()
                .filter(n -> n.getRecipientUserId() == recipientUserId)
                .collect(Collectors.toList());
    }
}
