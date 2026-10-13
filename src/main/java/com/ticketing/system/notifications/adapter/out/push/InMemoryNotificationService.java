package com.ticketing.system.notifications.adapter.out.push;

import com.ticketing.system.notifications.application.port.out.PushNotificationService;
import com.ticketing.system.notifications.domain.Notification;

import org.springframework.stereotype.Component;

// V1 stub for PushNotificationService — push channel adapter.
// V1: simulates a reachable channel by always returning true (no real delivery yet).
//     Sent notifications are also collected in memory (see getSentNotifications) so tests
//     can assert what was pushed; the dispatcher remains the durable store of record.
// V2/V3: replace with WebSocket / SSE / email when real-time push is in scope.

@Component
public class InMemoryNotificationService implements PushNotificationService {

    // V1 stub behaviour (as the class doc states): collect notifications in memory
    // instead of
    // throwing, so reservation/checkout flows complete. UC-35 replaces this with a
    // real push channel.
    private final java.util.List<Notification> sentNotifications = new java.util.concurrent.CopyOnWriteArrayList<>();

    @Override
    public boolean send(int recipientUserId, Notification notification) {
        // V1: always report success (no real channel yet) and record the notification so
        // tests can assert what was sent. Tests that need a failed/PENDING delivery should
        // mock PushNotificationService to return false rather than rely on this stub.
        sentNotifications.add(notification);
        return true;
    }

    @Override
    public boolean isReachable(int recipientUserId) {
        return true;
    }

    public java.util.List<Notification> getSentNotifications() {
        return java.util.List.copyOf(sentNotifications);
    }
}
