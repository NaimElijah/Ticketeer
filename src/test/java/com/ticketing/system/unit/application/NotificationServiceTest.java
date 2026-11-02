package com.ticketing.system.unit.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.OptimisticLockingFailureException;

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

    // The port's contract is "at least one recipient": a send without one is the caller's mistake, so it
    // is refused outright rather than stored as nothing (#187).
    @ParameterizedTest
    @NullAndEmptySource
    void GivenNoRecipients_WhenNotifyOutreach_ThenRefusedWithIllegalArgumentAndNothingIsDispatched(
            List<OutreachRecipient> recipients) {
        assertThrows(IllegalArgumentException.class,
                () -> service.notifyOutreach(recipients, "a system admin", "Venue change", "Hall B."));

        verifyNoInteractions(dispatcher);
    }

    // ---- Defect 1: A click on a notification that went back to PENDING is lost, silently (#187) ----

    private static final int RECIPIENT = 7;
    private static final int ANOTHER_MEMBER = 8;
    private static final String NOTIFICATION_ID = "notif-1";

    private final NotificationRepository notifications = mock(NotificationRepository.class);
    private final NotificationService readingService = new NotificationService(
            dispatcher, notifications, Clock.fixed(T0, ZoneOffset.UTC));
    private final DataAccessResourceFailureException storeDown =
            new DataAccessResourceFailureException("store unavailable");

    // A session ending on another device sends the member's SENT notifications back to PENDING, while
    // this device's bell still shows them; a click there must still record the read.
    @Test
    void GivenAPendingNotificationOfTheRecipient_WhenMarkRead_ThenItIsSavedAsRead() {
        Notification revertedToPending = notification(RECIPIENT, NotificationStatus.PENDING);
        when(notifications.findById(NOTIFICATION_ID)).thenReturn(revertedToPending);

        readingService.markRead(RECIPIENT, NOTIFICATION_ID);

        assertEquals(NotificationStatus.READ, revertedToPending.getStatus(),
                "a click on a notification sent back to PENDING should mark it read");
        verify(notifications).save(revertedToPending);
    }

    @Test
    void GivenTheStoreFailsOnTheLookup_WhenMarkRead_ThenTheFailurePropagates() {
        when(notifications.findById(NOTIFICATION_ID)).thenThrow(storeDown);

        DataAccessException thrown = assertThrows(DataAccessException.class,
                () -> readingService.markRead(RECIPIENT, NOTIFICATION_ID),
                "a store failure should reach the caller, not be logged and dropped");
        assertSame(storeDown, thrown);
    }

    @Test
    void GivenTheStoreFailsOnTheSave_WhenMarkRead_ThenTheFailurePropagates() {
        when(notifications.findById(NOTIFICATION_ID)).thenReturn(notification(RECIPIENT, NotificationStatus.SENT));
        doThrow(storeDown).when(notifications).save(any(Notification.class));

        DataAccessException thrown = assertThrows(DataAccessException.class,
                () -> readingService.markRead(RECIPIENT, NOTIFICATION_ID),
                "a store failure should reach the caller, not be logged and dropped");
        assertSame(storeDown, thrown);
    }

    @Test
    void GivenAnotherMembersNotification_WhenMarkRead_ThenItIsLeftUnreadAndNotSaved() {
        Notification theirs = notification(ANOTHER_MEMBER, NotificationStatus.SENT);
        when(notifications.findById(NOTIFICATION_ID)).thenReturn(theirs);

        readingService.markRead(RECIPIENT, NOTIFICATION_ID);

        assertEquals(NotificationStatus.SENT, theirs.getStatus(),
                "a member must not mark another member's notification read");
        verify(notifications, never()).save(any(Notification.class));
    }

    @Test
    void GivenAnUnknownNotificationId_WhenMarkRead_ThenNothingIsSaved() {
        when(notifications.findById(NOTIFICATION_ID)).thenReturn(null);

        readingService.markRead(RECIPIENT, NOTIFICATION_ID);

        verify(notifications, never()).save(any(Notification.class));
    }

    @Test
    void GivenAnAlreadyReadNotification_WhenMarkRead_ThenItIsNotSavedAgain() {
        Notification alreadyRead = notification(RECIPIENT, NotificationStatus.READ);
        when(notifications.findById(NOTIFICATION_ID)).thenReturn(alreadyRead);

        readingService.markRead(RECIPIENT, NOTIFICATION_ID);

        assertEquals(NotificationStatus.READ, alreadyRead.getStatus());
        verify(notifications, never()).save(any(Notification.class));
    }

    @Test
    void GivenTheStoreFailsWhileRevertingASentNotification_WhenRevertSentToPending_ThenTheFailurePropagates() {
        Notification sent = notification(RECIPIENT, NotificationStatus.SENT);
        when(notifications.findByRecipientAndStatus(RECIPIENT, NotificationStatus.SENT)).thenReturn(List.of(sent));
        doThrow(storeDown).when(notifications).save(sent);

        DataAccessException thrown = assertThrows(DataAccessException.class,
                () -> readingService.revertSentToPending(RECIPIENT),
                "a store failure should reach the caller, not be logged and dropped");
        assertSame(storeDown, thrown);
    }

    // A click marks a shown row READ, or another browser's delivery saves it, between this send-back's
    // read and its save: that row has nothing to send back, and the rest still go back to PENDING.
    @Test
    void GivenTheSecondOfThreeSavesLosesAnOptimisticLockRace_WhenRevertSentToPending_ThenTheFirstAndThirdAreSentBack() {
        Notification first = sentNotification("n1");
        Notification raced = sentNotification("n2");
        Notification third = sentNotification("n3");
        when(notifications.findByRecipientAndStatus(RECIPIENT, NotificationStatus.SENT))
                .thenReturn(List.of(first, raced, third));
        doThrow(new OptimisticLockingFailureException("row updated by another transaction"))
                .when(notifications).save(raced);

        readingService.revertSentToPending(RECIPIENT);

        verify(notifications).save(first);
        verify(notifications).save(third);
        assertEquals(NotificationStatus.PENDING, first.getStatus());
        assertEquals(NotificationStatus.PENDING, third.getStatus());
    }

    private static Notification sentNotification(String id) {
        return new Notification(id, RECIPIENT, NotificationType.PURCHASE_CONFIRMED, NotificationStatus.SENT,
                "Purchase completed successfully.", LocalDateTime.ofInstant(T0, ZoneOffset.UTC));
    }

    private static Notification notification(int recipientUserId, NotificationStatus status) {
        return new Notification(NOTIFICATION_ID, recipientUserId, NotificationType.PURCHASE_CONFIRMED, status,
                "Purchase completed successfully.", LocalDateTime.ofInstant(T0, ZoneOffset.UTC));
    }

    // ---- Defect 5: Notification text shows raw doubles and ids (#187) ----

    // A locale that writes decimals with a comma: the total must still read 35.98.
    private static final Locale COMMA_DECIMAL_LOCALE = Locale.GERMANY;

    @Test
    void GivenATotalWithThreeDecimals_WhenNotifyPurchaseCompleted_ThenItReadsWithTwoDecimalsWhateverTheJvmLocale() {
        Locale jvmLocale = Locale.getDefault();
        // setDefault(Locale) resets every category, so the format category is put back on its own.
        Locale jvmFormatLocale = Locale.getDefault(Locale.Category.FORMAT);
        Locale.setDefault(COMMA_DECIMAL_LOCALE);
        try {
            service.notifyPurchaseCompleted(RECIPIENT, 35.982, List.of(1, 2));
        } finally {
            Locale.setDefault(jvmLocale);
            Locale.setDefault(Locale.Category.FORMAT, jvmFormatLocale);
        }

        assertEquals("Purchase completed successfully. Total price: 35.98", dispatchedMessage(),
                "the total should read 35.98, two decimals with a point under any JVM locale");
    }

    static Stream<Arguments> reservationNotices() {
        return Stream.of(
                Arguments.of("reservation succeeded",
                        (Consumer<NotificationService>) s -> s.notifyTicketReservationSuccess(RECIPIENT, 4021, 3, 2),
                        "You reserved 2 tickets."),
                Arguments.of("reservation failed",
                        (Consumer<NotificationService>) s -> s.notifyTicketReservationFailure(RECIPIENT, 4021, 3,
                                "Sold out"),
                        "Your ticket reservation failed: Sold out"),
                Arguments.of("reservation removal succeeded",
                        (Consumer<NotificationService>) s -> s.notifyRemoveTicketReservationSuccess(RECIPIENT, 4021,
                                3, 2),
                        "You removed 2 tickets from your reservation."),
                Arguments.of("reservation removal failed",
                        (Consumer<NotificationService>) s -> s.notifyRemoveTicketReservationFailure(RECIPIENT, 4021,
                                3, "Order not found"),
                        "Removing your reserved tickets failed: Order not found"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("reservationNotices")
    void GivenAReservationNotice_WhenItsNotificationIsComposed_ThenTheTextCarriesNoRawIds(String notice,
            Consumer<NotificationService> notify, String expected) {
        notify.accept(service);

        assertEquals(expected, dispatchedMessage(), "the member reads this text, so it shows no raw ids");
    }

    static Stream<Arguments> reservationNoticesAndWhatTheyMustSay() {
        return Stream.of(
                Arguments.of("reservation succeeded",
                        (Consumer<NotificationService>) s -> s.notifyTicketReservationSuccess(RECIPIENT, 4021, 3, 2),
                        "2 tickets"),
                Arguments.of("one ticket reserved",
                        (Consumer<NotificationService>) s -> s.notifyTicketReservationSuccess(RECIPIENT, 4021, 3, 1),
                        "1 ticket."),
                Arguments.of("reservation failed",
                        (Consumer<NotificationService>) s -> s.notifyTicketReservationFailure(RECIPIENT, 4021, 3,
                                "Sold out"),
                        "Sold out"),
                Arguments.of("reservation removal succeeded",
                        (Consumer<NotificationService>) s -> s.notifyRemoveTicketReservationSuccess(RECIPIENT, 4021,
                                3, 2),
                        "2 tickets"),
                Arguments.of("reservation removal failed",
                        (Consumer<NotificationService>) s -> s.notifyRemoveTicketReservationFailure(RECIPIENT, 4021,
                                3, "Order not found"),
                        "Order not found"));
    }

    // Without the ids, the quantity or the reason is all the member learns, so it must stay, in words.
    @ParameterizedTest(name = "{0}")
    @MethodSource("reservationNoticesAndWhatTheyMustSay")
    void GivenAReservationNotice_WhenItsNotificationIsComposed_ThenTheTextKeepsTheQuantityOrTheReasonInWords(
            String notice, Consumer<NotificationService> notify, String mustSay) {
        notify.accept(service);

        String message = dispatchedMessage();
        assertTrue(message.contains(mustSay), "the text should say \"" + mustSay + "\", but was: " + message);
    }

    // The event's id stays in the notification's data for a link; the member reads only its name.
    @Test
    void GivenACancelledEvent_WhenNotifyEventCancelled_ThenTheTextNamesTheEventWithoutItsId() {
        service.notifyEventCancelled(RECIPIENT, 4021, "Summer Jazz Night");

        ArgumentCaptor<Notification> sent = ArgumentCaptor.forClass(Notification.class);
        verify(dispatcher).dispatch(sent.capture());
        assertEquals("Event 'Summer Jazz Night' has been cancelled. Refunds have been processed.",
                sent.getValue().getMessage());
        assertEquals(4021, sent.getValue().getData().get("eventId"));
    }

    private String dispatchedMessage() {
        ArgumentCaptor<Notification> sent = ArgumentCaptor.forClass(Notification.class);
        verify(dispatcher).dispatch(sent.capture());
        return sent.getValue().getMessage();
    }
}
