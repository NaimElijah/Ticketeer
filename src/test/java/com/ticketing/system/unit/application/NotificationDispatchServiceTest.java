package com.ticketing.system.unit.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.shared.dto.NotificationDTO;
import com.ticketing.system.notifications.application.port.out.PushNotificationService;
import com.ticketing.system.notifications.application.service.NotificationDispatchService;
import com.ticketing.system.notifications.application.port.out.NotificationRepository;
import com.ticketing.system.notifications.domain.Notification;
import com.ticketing.system.notifications.domain.NotificationStatus;
import com.ticketing.system.notifications.domain.NotificationType;

class NotificationDispatchServiceTest {

    private NotificationRepository mockRepo;
    private PushNotificationService mockPushService;
    private NotificationDispatchService service;

    @BeforeEach
    void setUp() {
        mockRepo = mock(NotificationRepository.class);
        mockPushService = mock(PushNotificationService.class);
        service = new NotificationDispatchService(mockRepo, mockPushService);
    }

    @Test
    void dispatch_savesPendingNotification() {
        Notification notification = new Notification(
                "notif-1",
                42,
                NotificationType.PURCHASE_CONFIRMED,
                NotificationStatus.PENDING,
                "Your purchase has been confirmed",
                LocalDateTime.now());

        service.dispatch(notification);

        verify(mockRepo, times(1)).save(notification);
        assertEquals(NotificationStatus.PENDING, notification.getStatus());
    }

    @Test
    void dispatch_rejectsNonPendingNotification() {
        Notification notification = new Notification(
                "notif-1",
                42,
                NotificationType.PURCHASE_CONFIRMED,
                NotificationStatus.SENT,
                "msg",
                LocalDateTime.now());

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> service.dispatch(notification));
        verify(mockRepo, never()).save(any());
    }

    /* UC-37: deliver pending notifications */
    @Test
    void givenPendingNotifications_whenDeliverPending_thenAllDelivered() {
        int userId = 42;

        Notification notif1 = new Notification(
                "notif-1",
                userId,
                NotificationType.PURCHASE_CONFIRMED,
                NotificationStatus.PENDING,
                "Your purchase has been confirmed",
                LocalDateTime.now());

        Notification notif2 = new Notification(
                "notif-2",
                userId,
                NotificationType.EVENT_SOLD_OUT,
                NotificationStatus.PENDING,
                "An event you're interested in is sold out",
                LocalDateTime.now());

        List<Notification> pendingNotifications = new ArrayList<>();
        pendingNotifications.add(notif1);
        pendingNotifications.add(notif2);

        // Mock repository to return pending notifications
        when(mockRepo.findByRecipientAndStatus(userId, NotificationStatus.PENDING))
                .thenReturn(pendingNotifications);

        when(mockPushService.send(userId, notif1)).thenReturn(true);
        when(mockPushService.send(userId, notif2)).thenReturn(true);

        // Call the service method
        List<NotificationDTO> result = service.deliverPending(userId);

        // Verify repository query for pending notifications
        verify(mockRepo, times(1)).findByRecipientAndStatus(userId, NotificationStatus.PENDING);
        verify(mockPushService, times(1)).send(userId, notif1);
        verify(mockPushService, times(1)).send(userId, notif2);
        verify(mockRepo, times(1)).save(notif1);
        verify(mockRepo, times(1)).save(notif2);

        // Verify all returned DTOs have SENT status
        assertEquals(2, result.size());
        assertEquals("SENT", result.get(0).status());
        assertEquals("SENT", result.get(1).status());
    }

    /* UC-37: no pending notifications is no-op */
    @Test
    void givenNoPending_whenDeliverPending_thenNoop() {
        int userId = 42;

        // Mock repository to return empty list (no pending notifications)
        when(mockRepo.findByRecipientAndStatus(userId, NotificationStatus.PENDING))
                .thenReturn(new ArrayList<>());

        // Call the service method
        List<NotificationDTO> result = service.deliverPending(userId);

        // Verify repository queried for pending notifications
        verify(mockRepo, times(1)).findByRecipientAndStatus(userId, NotificationStatus.PENDING);

        // Verify notification service was never called
        verify(mockPushService, never()).send(any(Integer.class), any(Notification.class));

        // Verify repository never saved anything
        verify(mockRepo, never()).save(any());

        // Verify empty list is returned
        assertTrue(result.isEmpty());
    }
}
