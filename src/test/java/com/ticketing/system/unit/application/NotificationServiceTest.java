package com.ticketing.system.unit.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DataAccessResourceFailureException;

import com.ticketing.system.notifications.application.port.in.OutreachRecipient;
import com.ticketing.system.notifications.application.port.out.NotificationRepository;
import com.ticketing.system.notifications.application.service.NotificationDispatchService;
import com.ticketing.system.notifications.application.service.NotificationService;
import com.ticketing.system.notifications.domain.Notification;
import com.ticketing.system.notifications.domain.NotificationStatus;
import com.ticketing.system.notifications.domain.NotificationType;

class NotificationServiceTest {

    // Notifications are stamped from the injected clock, as UTC wall time (ADR-008).
    private static final Instant T0 = Instant.parse("2027-07-15T17:00:00Z");

    private final NotificationDispatchService dispatcher = mock(NotificationDispatchService.class);
    private final NotificationService service = new NotificationService(
            dispatcher, mock(NotificationRepository.class), Clock.fixed(T0, ZoneOffset.UTC));

    @Test
    void GivenFixedClock_WhenNotifyPurchaseCompleted_ThenTheNotificationIsCreatedAtTheClock() {
        service.notifyPurchaseCompleted(7, 120.0, List.of(1, 2));

        ArgumentCaptor<Notification> sent = ArgumentCaptor.forClass(Notification.class);
        verify(dispatcher).dispatch(sent.capture());
        assertEquals(LocalDateTime.ofInstant(T0, ZoneOffset.UTC), sent.getValue().getCreatedAt());
    }

    // A manager invitation is still pending, so it must not read as a role change (#187).
    @Test
    void GivenPendingManagerAppointment_WhenNotifyManagerAppointmentPending_ThenAPendingAppointmentNotificationIsDispatched() {
        service.notifyManagerAppointmentPending(7, 100, "Acme");

        ArgumentCaptor<Notification> sent = ArgumentCaptor.forClass(Notification.class);
        verify(dispatcher).dispatch(sent.capture());
        Notification notification = sent.getValue();
        assertEquals(7, notification.getRecipientUserId());
        assertEquals(NotificationType.MANAGER_APPOINTMENT_PENDING, notification.getType());
        assertEquals(NotificationStatus.PENDING, notification.getStatus());
        assertEquals("You have a pending manager appointment for company 'Acme'. Please accept or reject it.",
                notification.getMessage());
        assertEquals(Map.of("companyId", 100, "companyName", "Acme"), notification.getData());
    }

    // ---- An outreach to more recipients than the notification queue holds drops alerts (#187) ----

    // Each recipient's notification reads as a single new message would, and goes to the dispatcher on
    // its own, in the recipients' order, so each is stored on its own transaction (ADR-019).
    @Test
    void GivenTwoOutreachRecipients_WhenNotifyOutreach_ThenEachGetsANewMessageNotificationDispatchedInOrder() {
        service.notifyOutreach(List.of(
                new OutreachRecipient(7, "conv-7"), new OutreachRecipient(8, "conv-8")),
                "a system admin", "Venue change", "Hall B.");

        ArgumentCaptor<Notification> sent = ArgumentCaptor.forClass(Notification.class);
        verify(dispatcher, times(2)).dispatch(sent.capture());
        verifyNoMoreInteractions(dispatcher);
        List<Notification> notifications = sent.getAllValues();
        assertEquals(List.of(7, 8), notifications.stream().map(Notification::getRecipientUserId).toList());
        for (Notification n : notifications) {
            assertEquals(NotificationType.DIRECT_MESSAGE, n.getType());
            assertEquals(NotificationStatus.PENDING, n.getStatus());
            assertEquals("New message from a system admin: Venue change", n.getMessage());
            assertEquals(LocalDateTime.ofInstant(T0, ZoneOffset.UTC), n.getCreatedAt());
            assertEquals(Map.of("conversationId", "conv-" + n.getRecipientUserId(), "senderLabel", "a system admin",
                    "subject", "Venue change", "snippet", "Hall B."), n.getData());
        }
    }

    // A store failure stops the fan-out where it happens: the recipients before it were dispatched, the
    // ones after it never are, and the failure reaches the async handler (ADR-004, ADR-019).
    @Test
    void GivenTheSecondOfThreeDispatchesFails_WhenNotifyOutreach_ThenItPropagatesAndTheThirdIsNeverDispatched() {
        DataAccessResourceFailureException storeDown = new DataAccessResourceFailureException("connection refused");
        doThrow(storeDown).when(dispatcher).dispatch(argThat(n -> n.getRecipientUserId() == 8));

        DataAccessResourceFailureException thrown = assertThrows(DataAccessResourceFailureException.class,
                () -> service.notifyOutreach(List.of(new OutreachRecipient(7, "conv-7"),
                        new OutreachRecipient(8, "conv-8"), new OutreachRecipient(9, "conv-9")),
                        "a system admin", "Venue change", "Hall B."));

        assertEquals(storeDown, thrown);
        InOrder order = inOrder(dispatcher);
        order.verify(dispatcher).dispatch(argThat(n -> n.getRecipientUserId() == 7));
        order.verify(dispatcher).dispatch(argThat(n -> n.getRecipientUserId() == 8));
        verify(dispatcher, never()).dispatch(argThat(n -> n.getRecipientUserId() == 9));
        verify(dispatcher, times(2)).dispatch(any());
    }
}
