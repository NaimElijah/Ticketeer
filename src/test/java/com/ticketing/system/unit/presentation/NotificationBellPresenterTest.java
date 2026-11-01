package com.ticketing.system.unit.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import ch.qos.logback.classic.Level;
import jakarta.persistence.PersistenceException;

import com.ticketing.system.notifications.application.port.in.INotificationService;
import com.ticketing.system.shared.dto.AuthTokenDTO;
import com.ticketing.system.testutil.LogCapture;
import com.ticketing.system.ui.presenters.notifications.NotificationBellPresenter;
import com.ticketing.system.ui.presenters.notifications.NotificationBellPresenter.MarkReadOutcome;
import com.ticketing.system.ui.session.AuthSession;
import com.ticketing.system.ui.session.MapBackedVaadinSession;
import com.ticketing.system.ui.support.DisplayTime;
import com.ticketing.system.ui.support.ServiceErrors;

/**
 * {@link NotificationBellPresenter#markRead} tells the bell whether the read was stored, and why not, so
 * the bell marks the item read only when the store agrees, the unread badge never hides a notification
 * the next login shows again, and a refused read is reported (#187).
 */
class NotificationBellPresenterTest {

    private static final String TOKEN = "member-jwt";
    private static final int USER_ID = 42;
    private static final String NOTIFICATION_ID = "notif-1";

    private INotificationService notificationService;
    private NotificationBellPresenter presenter;

    @BeforeEach
    void setUp() {
        MapBackedVaadinSession.install();
        notificationService = mock(INotificationService.class);
        presenter = new NotificationBellPresenter(notificationService, mock(DisplayTime.class));
    }

    @AfterEach
    void tearDown() {
        MapBackedVaadinSession.uninstall();
    }

    @Test
    void GivenASignedInMemberAndAWorkingStore_WhenMarkRead_ThenTheReadIsStoredAndReportedAsDone() {
        signIn();

        MarkReadOutcome outcome = presenter.markRead(NOTIFICATION_ID);

        assertInstanceOf(MarkReadOutcome.Success.class, outcome, "a read the store saved should be reported as done");
        verify(notificationService).markRead(USER_ID, NOTIFICATION_ID);
    }

    @Test
    void GivenTheStoreIsUnreachable_WhenMarkRead_ThenItFailsWithTheOutageMessage() {
        signIn();
        doThrow(new DataAccessResourceFailureException("store unavailable"))
                .when(notificationService).markRead(USER_ID, NOTIFICATION_ID);

        MarkReadOutcome outcome = presenter.markRead(NOTIFICATION_ID);

        MarkReadOutcome.Failure failure = assertInstanceOf(MarkReadOutcome.Failure.class, outcome,
                "a read the store did not save should be reported as not done, so the bell keeps it unread");
        assertEquals(ServiceErrors.DB_UNAVAILABLE_MESSAGE, failure.reason());
    }

    // LoggingAspect logs a failed service call at WARN with its message only, no stack; an internal
    // failure needs the ERROR with the exception that ServiceErrors writes for every presenter.
    @Test
    void GivenAnInternalFailure_WhenMarkRead_ThenItFailsWithTheGenericTextAndIsLoggedAtErrorWithTheException() {
        signIn();
        doThrow(new PersistenceException("mapping broke"))
                .when(notificationService).markRead(USER_ID, NOTIFICATION_ID);

        try (LogCapture logs = LogCapture.of(ServiceErrors.class)) {
            MarkReadOutcome outcome = presenter.markRead(NOTIFICATION_ID);

            MarkReadOutcome.Failure failure = assertInstanceOf(MarkReadOutcome.Failure.class, outcome,
                    "a read the store did not save should be reported as not done");
            assertEquals(ServiceErrors.GENERIC_FAILURE_MESSAGE, failure.reason());
            assertEquals(List.of(PersistenceException.class.getName()), logs.throwableClassNamesAt(Level.ERROR),
                    "an internal failure should be logged at ERROR with the exception attached");
        }
    }

    @Test
    void GivenNoSignedInMember_WhenMarkRead_ThenNothingIsStoredAndItIsNotAuthenticated() {
        MarkReadOutcome outcome = presenter.markRead(NOTIFICATION_ID);

        assertInstanceOf(MarkReadOutcome.NotAuthenticated.class, outcome, "with nobody signed in there is no read to store");
        verify(notificationService, never()).markRead(anyInt(), anyString());
    }

    // Admin and member ids share one id space (ADR-012), so an admin's id would name some member's
    // notifications; the poller and the session listener skip admins for the same reason.
    @Test
    void GivenAnAdminSession_WhenMarkRead_ThenItIsNotAuthenticatedAndNothingIsCalled() {
        AuthSession.storeAuth(new AuthTokenDTO(TOKEN, 0L, USER_ID, "admin", true));

        MarkReadOutcome outcome = presenter.markRead(NOTIFICATION_ID);

        assertInstanceOf(MarkReadOutcome.NotAuthenticated.class, outcome,
                "an admin session has no member notifications to mark");
        verifyNoInteractions(notificationService);
    }

    private static void signIn() {
        AuthSession.storeAuth(new AuthTokenDTO(TOKEN, 0L, USER_ID, "member", false));
    }
}
