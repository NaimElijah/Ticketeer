package com.ticketing.system.notifications.adapter.out.push;

import com.ticketing.system.notifications.application.port.out.PushNotificationService;
import com.ticketing.system.notifications.domain.Notification;

import org.springframework.stereotype.Component;

/**
 * Stand-in push channel: reports every notification as delivered and keeps nothing. There is no real
 * channel yet, so the stored {@link Notification} rows are the record of what a user was sent; UC-35
 * replaces this with WebSocket, SSE or email delivery.
 *
 * <p>It holds no state on purpose. It is the only {@link PushNotificationService} in every profile, and
 * each login, poll and session end re-delivers notifications, so anything kept here would grow for the
 * life of the process. Tests that need a failed delivery mock the port instead.
 */
@Component
public class InMemoryNotificationService implements PushNotificationService {

    @Override
    public boolean send(int recipientUserId, Notification notification) {
        return true;
    }

    @Override
    public boolean isReachable(int recipientUserId) {
        return true;
    }
}
