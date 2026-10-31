package com.ticketing.system.unit.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
}
