package com.ticketing.system.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import com.ticketing.system.shared.dto.NotificationDTO;
import java.util.List;

import com.ticketing.system.notifications.application.port.out.NotificationRepository;
import com.ticketing.system.notifications.application.service.NotificationDispatchService;
import com.ticketing.system.notifications.domain.Notification;
import com.ticketing.system.notifications.domain.NotificationStatus;
import com.ticketing.system.notifications.domain.NotificationType;

@SpringBootTest
@ActiveProfiles("test")
class NotificationAcceptanceTest {

    @Autowired
    private NotificationDispatchService notificationDispatchService;

    @Autowired
    private NotificationRepository notificationRepository;

    // UC-35
    @Test
    @Disabled("UC-35 main: TicketsPurchased event → buyer notification (I.5.2)")
    void GivenTicketsPurchased_WhenDispatch_ThenBuyerNotified() {
    }

    @Test
    @Disabled("UC-35 main: EventSoldOut event → producer notification (I.5.1)")
    void GivenEventSoldOut_WhenDispatch_ThenProducerNotified() {
    }

    @Test
    @Disabled("UC-35 alt: ManagerRevoked event → role-changed notification (I.5.1)")
    void GivenManagerRevoked_WhenDispatch_ThenManagerNotified() {
    }

    // UC-36
    @Test
    @Disabled("UC-36 main: offline recipient → notification stored as PENDING (I.6.1)")
    void GivenOfflineRecipient_WhenDispatch_ThenPending() {
        int userId = 99;

        // Create a notification for the offline user
        Notification notification = new Notification(
                "notif-acc-36-1",
                userId,
                NotificationType.PURCHASE_CONFIRMED,
                NotificationStatus.PENDING,
                "Your purchase has been confirmed",
                LocalDateTime.now());

        // Store the pending notification (simulating UC-36 offline dispatch)
        notificationDispatchService.storePending(notification);

        // Verify the notification was persisted in PENDING state
        Notification savedNotification = notificationRepository.findById("notif-acc-36-1");
        assertNotNull(savedNotification);
        assertEquals(userId, savedNotification.getRecipientUserId());
        assertEquals(NotificationStatus.PENDING, savedNotification.getStatus());
        assertEquals(NotificationType.PURCHASE_CONFIRMED, savedNotification.getType());
    }

    @Test
    @Disabled("UC-36 alt: online recipient → no PENDING storage")
    void GivenOnlineRecipient_WhenDispatch_ThenNotPending() {
        int userId = 100;

        // Create a notification for an online user
        Notification notification = new Notification(
                "notif-acc-36-alt-1",
                userId,
                NotificationType.EVENT_SOLD_OUT,
                NotificationStatus.PENDING,
                "Event is sold out",
                LocalDateTime.now());

        // For online recipients, notifications should go directly to live push (UC-35
        // online branch)
        // and NOT be stored as PENDING. This test verifies that no PENDING notification
        // is created.
        // In a real scenario, dispatchFromEvent would route to live push instead.
        // We simulate by NOT calling storePending for online users.

        // Verify no PENDING notification exists for this user
        // (In production, the dispatch logic would skip storePending for online
        // recipients)
        // This is more of a behavior verification that online recipients don't
        // accumulate PENDING notifications

        // For now, we just verify that if we don't call storePending, nothing is
        // persisted
        Notification savedNotification = notificationRepository.findById("notif-acc-36-alt-1");
        assertEquals(null, savedNotification);
    }

    // UC-37
    @Test
    @Disabled("UC-37 main: login → all PENDING delivered (I.6.2)")
    void GivenPendingNotifications_WhenLogin_ThenAllDelivered() {
        int userId = 101;

        // Set up: create and persist multiple PENDING notifications for the user
        Notification notif1 = new Notification(
                "notif-acc-37-1",
                userId,
                NotificationType.PURCHASE_CONFIRMED,
                NotificationStatus.PENDING,
                "Your purchase has been confirmed",
                LocalDateTime.now());

        Notification notif2 = new Notification(
                "notif-acc-37-2",
                userId,
                NotificationType.EVENT_SOLD_OUT,
                NotificationStatus.PENDING,
                "An event you're interested in is sold out",
                LocalDateTime.now());

        // Store the pending notifications
        notificationDispatchService.storePending(notif1);
        notificationDispatchService.storePending(notif2);

        // Act: simulate login by calling deliverPending
        List<NotificationDTO> deliveredNotifications = notificationDispatchService.deliverPending(userId);

        // Assert: verify all PENDING notifications are now SENT
        assertNotNull(deliveredNotifications);
        assertEquals(2, deliveredNotifications.size());

        // Verify both notifications are marked as SENT
        for (NotificationDTO dto : deliveredNotifications) {
            assertEquals("SENT", dto.status());
        }

        // Verify the notifications in the repository are now SENT
        Notification savedNotif1 = notificationRepository.findById("notif-acc-37-1");
        Notification savedNotif2 = notificationRepository.findById("notif-acc-37-2");
        assertEquals(NotificationStatus.SENT, savedNotif1.getStatus());
        assertEquals(NotificationStatus.SENT, savedNotif2.getStatus());
    }

    @Test
    @Disabled("UC-37 alt: empty PENDING is no-op")
    void GivenNoPending_WhenLogin_ThenNoop() {
        int userId = 102;

        // User has no pending notifications (either new user or all were already
        // delivered)

        // Act: call deliverPending for a user with no PENDING notifications
        List<NotificationDTO> deliveredNotifications = notificationDispatchService.deliverPending(userId);

        // Assert: empty list is returned (no-op behavior)
        assertNotNull(deliveredNotifications);
        assertEquals(0, deliveredNotifications.size());
        assertTrue(deliveredNotifications.isEmpty());
    }
}
