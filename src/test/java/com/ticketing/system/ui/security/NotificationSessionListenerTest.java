package com.ticketing.system.ui.security;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.ticketing.system.notifications.application.port.in.INotificationService;
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
 * <p>The listener reads the destroyed session's attributes directly (there is no current session
 * while it is destroyed), so the attribute names below are the ones {@code AuthSession} writes.
 */
class NotificationSessionListenerTest {

    private static final String SIGNED_IN_ATTRIBUTE = "authSession.signedIn";
    private static final String USER_ID_ATTRIBUTE = "authSession.userId";
    private static final String ADMIN_ATTRIBUTE = "authSession.admin";
    private static final int SHARED_ID = 1;

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

    @Test
    void GivenSignedInAdminSession_WhenDestroyed_ThenNoNotificationIsRevertedForTheAdminsId() {
        destroyListener.sessionDestroy(destroyEventFor(sessionWith(true, SHARED_ID, true)));

        verify(notificationService, never()).revertSentToPending(anyInt());
    }

    @Test
    void GivenSignedInMemberSession_WhenDestroyed_ThenTheMembersSentNotificationsAreReverted() {
        destroyListener.sessionDestroy(destroyEventFor(sessionWith(true, SHARED_ID, false)));

        verify(notificationService).revertSentToPending(SHARED_ID);
    }

    private static VaadinSession sessionWith(boolean signedIn, int userId, boolean admin) {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(SIGNED_IN_ATTRIBUTE, signedIn);
        attributes.put(USER_ID_ATTRIBUTE, userId);
        attributes.put(ADMIN_ATTRIBUTE, admin);
        VaadinSession session = mock(VaadinSession.class);
        when(session.getAttribute(anyString())).thenAnswer(call -> attributes.get(call.getArgument(0, String.class)));
        return session;
    }

    private static SessionDestroyEvent destroyEventFor(VaadinSession session) {
        SessionDestroyEvent event = mock(SessionDestroyEvent.class);
        when(event.getSession()).thenReturn(session);
        return event;
    }
}
