package com.ticketing.system.unit.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.ticketing.system.notifications.adapter.in.event.NotificationEventListener;
import com.ticketing.system.notifications.application.port.in.INotificationService;
import com.ticketing.system.notifications.application.port.in.OutreachRecipient;
import com.ticketing.system.shared.event.ManagerAppointmentPendingNotice;
import com.ticketing.system.shared.event.OutreachSentNotice;

/**
 * Unit test for {@link NotificationEventListener}, the notifications inbound adapter for the
 * {@code shared.event} notices. Delivery after commit on the {@code notify-} thread is specified by
 * {@code NotificationDispatchAcceptanceTest} and enforced by {@code HexagonalRulesTest}.
 */
class NotificationEventListenerTest {

    private final INotificationService notificationService = mock(INotificationService.class);
    private final NotificationEventListener listener = new NotificationEventListener(notificationService);

    // A pending manager invitation is announced as pending, never as a role change (#187).
    @Test
    void GivenManagerAppointmentPendingNotice_WhenOn_ThenOnlyAPendingManagerAppointmentIsNotified() {
        listener.on(new ManagerAppointmentPendingNotice(7, 100, "Acme"));

        verify(notificationService).notifyManagerAppointmentPending(7, 100, "Acme");
        verifyNoMoreInteractions(notificationService);
    }

    // One outreach is one notice and one call to the port, whatever its recipient count (ADR-019, #187).
    // The listener maps the notice's recipients onto the port's own record, in order.
    @Test
    void GivenOutreachSentNotice_WhenOn_ThenEveryRecipientIsNotifiedInOneCall() {
        listener.on(new OutreachSentNotice(List.of(
                new OutreachSentNotice.Recipient(7, "conv-7"), new OutreachSentNotice.Recipient(8, "conv-8")),
                "a system admin", "Venue change", "Hall B."));

        verify(notificationService).notifyOutreach(
                List.of(new OutreachRecipient(7, "conv-7"), new OutreachRecipient(8, "conv-8")),
                "a system admin", "Venue change", "Hall B.");
        verifyNoMoreInteractions(notificationService);
    }
}
