package com.ticketing.system.unit.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

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
import com.ticketing.system.ui.session.AuthSession;
import com.ticketing.system.ui.session.MapBackedVaadinSession;
import com.ticketing.system.ui.support.DisplayTime;
import com.ticketing.system.ui.support.ServiceErrors;

/**
 * {@link NotificationBellPresenter#markRead} tells the bell whether the read was stored, so the bell
 * marks the item read only when the store agrees and the unread badge never hides a notification the
 * next login shows again (#187).
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

        boolean marked = presenter.markRead(NOTIFICATION_ID);

        assertTrue(marked, "a read the store saved should be reported as done");
        verify(notificationService).markRead(USER_ID, NOTIFICATION_ID);
    }

    @Test
    void GivenTheStoreFails_WhenMarkRead_ThenItIsReportedAsNotDone() {
        signIn();
        doThrow(new DataAccessResourceFailureException("store unavailable"))
                .when(notificationService).markRead(USER_ID, NOTIFICATION_ID);

        boolean marked = presenter.markRead(NOTIFICATION_ID);

        assertFalse(marked, "a read the store did not save should be reported as not done, so the bell keeps it unread");
    }

    // LoggingAspect logs a failed service call at WARN with its message only, no stack; an internal
    // failure needs the ERROR with the exception that ServiceErrors writes for every presenter.
    @Test
    void GivenAnInternalFailure_WhenMarkRead_ThenItIsReportedAsNotDoneAndLoggedAtErrorWithTheException() {
        signIn();
        doThrow(new PersistenceException("mapping broke"))
                .when(notificationService).markRead(USER_ID, NOTIFICATION_ID);

        try (LogCapture logs = LogCapture.of(ServiceErrors.class)) {
            boolean marked = presenter.markRead(NOTIFICATION_ID);

            assertFalse(marked, "a read the store did not save should be reported as not done");
            assertEquals(List.of(PersistenceException.class.getName()), logs.throwableClassNamesAt(Level.ERROR),
                    "an internal failure should be logged at ERROR with the exception attached");
        }
    }

    @Test
    void GivenNoSignedInMember_WhenMarkRead_ThenNothingIsStoredAndItIsReportedAsNotDone() {
        boolean marked = presenter.markRead(NOTIFICATION_ID);

        assertFalse(marked, "with nobody signed in there is no read to store");
        verify(notificationService, never()).markRead(anyInt(), anyString());
    }

    private static void signIn() {
        AuthSession.storeAuth(new AuthTokenDTO(TOKEN, 0L, USER_ID, "member", false));
    }
}
