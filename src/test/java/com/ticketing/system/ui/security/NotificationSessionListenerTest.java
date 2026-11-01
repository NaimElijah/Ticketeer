package com.ticketing.system.ui.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;

import ch.qos.logback.classic.Level;

import com.ticketing.system.notifications.application.port.in.INotificationService;
import com.ticketing.system.shared.dto.AuthTokenDTO;
import com.ticketing.system.testutil.LogCapture;
import com.ticketing.system.ui.session.AuthSession;
import com.ticketing.system.ui.session.MapBackedVaadinSession;
import com.vaadin.flow.server.ServiceInitEvent;
import com.vaadin.flow.server.SessionDestroyEvent;
import com.vaadin.flow.server.SessionDestroyListener;
import com.vaadin.flow.server.VaadinService;
import com.vaadin.flow.server.VaadinSession;

/**
 * When a signed-in member's HTTP session ends, notifications sent to it but never opened go back
 * to PENDING. Notifications are addressed by bare user id, and member and admin ids share one id
 * space, so ending an admin's session must not touch the notifications of the member whose id
 * equals the admin's (#185).
 *
 * <p>Each session is arranged through {@link AuthSession}, as sign-in fills it, and is no longer the
 * current session when it is destroyed: a session-destroy callback runs with no current session, so
 * the listener has to read the session it is handed.
 */
class NotificationSessionListenerTest {

    private static final int SHARED_ID = 1;
    private static final long TOKEN_EXPIRY_EPOCH_MILLIS = 0L;

    private INotificationService notificationService;
    private SessionDestroyListener destroyListener;

    @BeforeEach
    void setUp() {
        notificationService = mock(INotificationService.class);
        VaadinService vaadinService = mock(VaadinService.class);
        ServiceInitEvent initEvent = mock(ServiceInitEvent.class);
        when(initEvent.getSource()).thenReturn(vaadinService);

        new NotificationSessionListener(notificationService).serviceInit(initEvent);

        ArgumentCaptor<SessionDestroyListener> registered = ArgumentCaptor.forClass(SessionDestroyListener.class);
        verify(vaadinService).addSessionDestroyListener(registered.capture());
        destroyListener = registered.getValue();
    }

    @AfterEach
    void tearDown() {
        MapBackedVaadinSession.uninstall();
    }

    @Test
    void GivenSignedInAdminSession_WhenDestroyed_ThenNoNotificationIsRevertedForTheAdminsId() {
        VaadinSession adminSession = sessionSignedInAs(
                new AuthTokenDTO("admin-jwt", TOKEN_EXPIRY_EPOCH_MILLIS, SHARED_ID, "admin", true));

        destroyListener.sessionDestroy(destroyEventFor(adminSession));

        verify(notificationService, never()).revertSentToPending(anyInt());
    }

    @Test
    void GivenSignedInMemberSession_WhenDestroyed_ThenTheMembersSentNotificationsAreReverted() {
        VaadinSession memberSession = sessionSignedInAs(
                new AuthTokenDTO("member-jwt", TOKEN_EXPIRY_EPOCH_MILLIS, SHARED_ID, "member"));

        destroyListener.sessionDestroy(destroyEventFor(memberSession));

        verify(notificationService).revertSentToPending(SHARED_ID);
    }

    @Test
    void GivenSignedOutSession_WhenDestroyed_ThenNothingIsReverted() {
        MapBackedVaadinSession.install();
        AuthSession.storeAuth(new AuthTokenDTO("member-jwt", TOKEN_EXPIRY_EPOCH_MILLIS, SHARED_ID, "member"));
        AuthSession.signOut();
        VaadinSession signedOut = VaadinSession.getCurrent();
        MapBackedVaadinSession.uninstall();

        destroyListener.sessionDestroy(destroyEventFor(signedOut));

        verify(notificationService, never()).revertSentToPending(anyInt());
    }

    // Nothing above a session-destroy callback can handle a failure, so the WARN is all that is left of
    // it: it must carry the exception and say whose notifications stayed SENT. The session must still be
    // unlocked, or every later request on it would block.
    @Test
    void GivenTheSendBackFails_WhenDestroyed_ThenAWarnCarriesTheExceptionAndTheUserAndTheSessionIsUnlocked() {
        VaadinSession memberSession = sessionSignedInAs(
                new AuthTokenDTO("member-jwt", TOKEN_EXPIRY_EPOCH_MILLIS, SHARED_ID, "member"));
        doThrow(new DataAccessResourceFailureException("store unavailable"))
                .when(notificationService).revertSentToPending(SHARED_ID);

        try (LogCapture logs = LogCapture.of(NotificationSessionListener.class)) {
            destroyListener.sessionDestroy(destroyEventFor(memberSession));

            assertEquals(List.of("SENT notifications not sent back on session end; they will not be shown again: "
                    + "userId=" + SHARED_ID), logs.messagesAt(Level.WARN));
            assertEquals(List.of(DataAccessResourceFailureException.class.getName()),
                    logs.throwableClassNamesAt(Level.WARN));
        }
        verify(memberSession).unlock();
    }

    /** A session signed in as {@code auth} through {@link AuthSession}, no longer the current one. */
    private static VaadinSession sessionSignedInAs(AuthTokenDTO auth) {
        MapBackedVaadinSession.install();
        AuthSession.storeAuth(auth);
        VaadinSession session = VaadinSession.getCurrent();
        MapBackedVaadinSession.uninstall();
        return session;
    }

    private static SessionDestroyEvent destroyEventFor(VaadinSession session) {
        SessionDestroyEvent event = mock(SessionDestroyEvent.class);
        when(event.getSession()).thenReturn(session);
        return event;
    }
}
