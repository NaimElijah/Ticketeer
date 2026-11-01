package com.ticketing.system.unit.application;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
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
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;

import ch.qos.logback.classic.Level;

import com.ticketing.system.shared.dto.NotificationDTO;
import com.ticketing.system.notifications.application.port.out.PushNotificationService;
import com.ticketing.system.notifications.application.service.NotificationDispatchService;
import com.ticketing.system.notifications.application.port.out.NotificationRepository;
import com.ticketing.system.notifications.domain.Notification;
import com.ticketing.system.notifications.domain.NotificationStatus;
import com.ticketing.system.notifications.domain.NotificationType;
import com.ticketing.system.testutil.LogCapture;

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

    // ---- The per-alert dispatch line is DEBUG (#187, ADR-019) ----

    // Under an outreach fan-out, dispatch runs once per recipient, and an alert being stored is not a
    // state transition anyone acts on, so its line stays below INFO.
    @Test
    void GivenAPendingNotification_WhenDispatch_ThenItsLineIsLoggedAtDebugNotInfo() {
        Notification notification = new Notification("notif-1", 42, NotificationType.DIRECT_MESSAGE,
                NotificationStatus.PENDING, "New message from a system admin: Venue change",
                LocalDateTime.of(2027, 7, 15, 17, 0));

        try (LogCapture log = LogCapture.of(NotificationDispatchService.class)) {
            service.dispatch(notification);

            assertEquals(List.of(), log.messagesAt(Level.INFO));
            assertEquals(List.of("Dispatching notification for userId=42, type=DIRECT_MESSAGE"),
                    log.messagesAt(Level.DEBUG));
        }
    }

    private static Notification pending(String id, int recipientUserId) {
        return new Notification(id, recipientUserId, NotificationType.DIRECT_MESSAGE, NotificationStatus.PENDING,
                "New message from a system admin: Venue change", LocalDateTime.of(2027, 7, 15, 17, 0));
    }

    // ---- Defect 2: A lost race in deliverPending fails the whole delivery (#187) ----

    // A poll on one device and a login on another can read the same PENDING row; the second save holds a
    // stale @Version. That row was saved first by the other delivery, so this one skips it, says so at
    // DEBUG (a race is routine, not degraded), and goes on. ConcurrentNotificationDeliveryJpaTest drives the
    // same race on the real adapter.
    @Test
    void GivenTheSecondOfThreeSavesLosesAnOptimisticLockRace_WhenDeliverPending_ThenItIsSkippedAtDebugAndTheOthersAreDelivered() {
        int userId = 42;
        Notification first = pending("notif-1", userId);
        Notification raced = pending("notif-2", userId);
        Notification third = pending("notif-3", userId);
        when(mockRepo.findByRecipientAndStatus(userId, NotificationStatus.PENDING))
                .thenReturn(List.of(first, raced, third));
        when(mockPushService.send(anyInt(), any(Notification.class))).thenReturn(true);
        doThrow(new OptimisticLockingFailureException("row updated by another transaction"))
                .when(mockRepo).save(raced);

        try (LogCapture logs = LogCapture.of(NotificationDispatchService.class)) {
            List<NotificationDTO> delivered = assertDoesNotThrow(() -> service.deliverPending(userId),
                    "a row another caller delivered first should be skipped, not fail this delivery");

            assertEquals(List.of("notif-1", "notif-3"),
                    delivered.stream().map(NotificationDTO::notificationId).toList(),
                    "the raced row belongs to the other caller's delivery; the rest are this caller's");
            verify(mockRepo).save(third);
            assertEquals(List.of("Notification id=notif-2 for userId=42 was saved first by another delivery"
                    + " or a click; skipped"),
                    logs.messagesAt(Level.DEBUG).stream().filter(m -> m.contains("notif-2")).toList(),
                    "the skip should be logged at DEBUG");
        }
    }

    // ---- Defect 3: Partial delivery is returned nowhere (#187) ----

    // Login and the poller show only the list deliverPending returns (login shows nothing when it
    // throws), and a row saved as SENT is never PENDING again, so the next delivery skips it too. What
    // was saved before a store failure therefore has to come back as the return value. Delivery stops at
    // the failure: the rows after it stay PENDING for the next login or poll.
    @Test
    void GivenTheSecondOfThreePendingSavesFails_WhenDeliverPending_ThenTheFirstIsReturnedAndTheThirdStaysPending() {
        int userId = 42;
        Notification first = pending("notif-1", userId);
        Notification second = pending("notif-2", userId);
        Notification third = pending("notif-3", userId);
        when(mockRepo.findByRecipientAndStatus(userId, NotificationStatus.PENDING))
                .thenReturn(List.of(first, second, third));
        when(mockPushService.send(anyInt(), any(Notification.class))).thenReturn(true);
        doThrow(new DataAccessResourceFailureException("store unavailable")).when(mockRepo).save(second);

        List<NotificationDTO> delivered = assertDoesNotThrow(() -> service.deliverPending(userId),
                "a store failure part-way should still hand back what was already delivered");

        assertEquals(List.of("notif-1"), delivered.stream().map(NotificationDTO::notificationId).toList(),
                "the row saved as SENT before the failure should reach the caller");
        assertEquals(NotificationStatus.PENDING, third.getStatus(),
                "the row after the failure should stay PENDING for the next delivery");
        verify(mockRepo, never()).save(third);
    }

    @Test
    void GivenTheSecondOfThreePendingSavesFails_WhenDeliverPending_ThenTheFailureIsLoggedAtWarnWithTheException() {
        int userId = 42;
        Notification second = pending("notif-2", userId);
        when(mockRepo.findByRecipientAndStatus(userId, NotificationStatus.PENDING))
                .thenReturn(List.of(pending("notif-1", userId), second, pending("notif-3", userId)));
        when(mockPushService.send(anyInt(), any(Notification.class))).thenReturn(true);
        doThrow(new DataAccessResourceFailureException("store unavailable")).when(mockRepo).save(second);

        try (LogCapture logs = LogCapture.of(NotificationDispatchService.class)) {
            service.deliverPending(userId);

            assertEquals(List.of(DataAccessResourceFailureException.class.getName()),
                    logs.throwableClassNamesAt(Level.WARN),
                    "a delivery cut short by the store is degraded, so WARN with the exception attached");
        }
    }

    // With the database down, the adapter's @Transactional save fails as it begins: a
    // CannotCreateTransactionException, which is no DataAccessException, and the commonest outage.
    @Test
    void GivenTheSecondOfThreePendingSavesCannotGetAConnection_WhenDeliverPending_ThenTheFirstIsReturnedAndTheThirdStaysPending() {
        int userId = 42;
        Notification first = pending("notif-1", userId);
        Notification second = pending("notif-2", userId);
        Notification third = pending("notif-3", userId);
        when(mockRepo.findByRecipientAndStatus(userId, NotificationStatus.PENDING))
                .thenReturn(List.of(first, second, third));
        when(mockPushService.send(anyInt(), any(Notification.class))).thenReturn(true);
        doThrow(new CannotCreateTransactionException("Could not open JPA EntityManager for transaction"))
                .when(mockRepo).save(second);

        List<NotificationDTO> delivered = assertDoesNotThrow(() -> service.deliverPending(userId),
                "a lost connection part-way should still hand back what was already delivered");

        assertEquals(List.of("notif-1"), delivered.stream().map(NotificationDTO::notificationId).toList());
        assertEquals(NotificationStatus.PENDING, third.getStatus());
        verify(mockRepo, never()).save(third);
    }

    // A connection lost while a row's own transaction commits can surface as a TransactionSystemException,
    // which is no DataAccessException: it is the same store failure, so it stops delivery the same way.
    @Test
    void GivenTheSecondOfThreeSavesFailsToCommit_WhenDeliverPending_ThenTheFirstIsReturnedAndTheThirdStaysPending() {
        int userId = 42;
        Notification first = pending("notif-1", userId);
        Notification second = pending("notif-2", userId);
        Notification third = pending("notif-3", userId);
        when(mockRepo.findByRecipientAndStatus(userId, NotificationStatus.PENDING))
                .thenReturn(List.of(first, second, third));
        when(mockPushService.send(anyInt(), any(Notification.class))).thenReturn(true);
        doThrow(new TransactionSystemException("Could not commit JPA transaction")).when(mockRepo).save(second);

        List<NotificationDTO> delivered = assertDoesNotThrow(() -> service.deliverPending(userId),
                "a commit failure part-way should still hand back what was already delivered");

        assertEquals(List.of("notif-1"), delivered.stream().map(NotificationDTO::notificationId).toList());
        assertEquals(NotificationStatus.PENDING, third.getStatus());
        verify(mockRepo, never()).save(third);
    }

    // Nothing is delivered yet when the read fails, so there is nothing to hand back; login and the
    // poller catch it.
    @Test
    void GivenTheStoreFailsReadingThePendingNotifications_WhenDeliverPending_ThenTheFailurePropagates() {
        DataAccessResourceFailureException storeDown = new DataAccessResourceFailureException("store unavailable");
        when(mockRepo.findByRecipientAndStatus(42, NotificationStatus.PENDING)).thenThrow(storeDown);

        assertSame(storeDown, assertThrows(DataAccessResourceFailureException.class,
                () -> service.deliverPending(42)));
        verify(mockRepo, never()).save(any());
    }

    // ---- Defect 4: INFO on every poll (#187) ----

    // The poller calls deliverPending for every signed-in tab once a minute, so a call with nothing to
    // deliver is the common case and must not reach the INFO log.
    @Test
    void GivenNothingPending_WhenDeliverPending_ThenNothingIsLoggedAtInfo() {
        when(mockRepo.findByRecipientAndStatus(42, NotificationStatus.PENDING)).thenReturn(List.of());

        try (LogCapture logs = LogCapture.of(NotificationDispatchService.class)) {
            service.deliverPending(42);

            assertEquals(List.of(), logs.messagesAt(Level.INFO),
                    "a delivery with nothing pending should log nothing at INFO");
        }
    }

    @Test
    void GivenOnePendingNotification_WhenDeliverPending_ThenOneLineIsLoggedAtInfo() {
        Notification waiting = pending("notif-1", 42);
        when(mockRepo.findByRecipientAndStatus(42, NotificationStatus.PENDING)).thenReturn(List.of(waiting));
        when(mockPushService.send(42, waiting)).thenReturn(true);

        try (LogCapture logs = LogCapture.of(NotificationDispatchService.class)) {
            service.deliverPending(42);

            List<String> info = logs.messagesAt(Level.INFO);
            assertEquals(1, info.size(), "a delivery that delivers something should log one INFO line, but logged " + info);
        }
    }
}
