package com.ticketing.system.ui.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.OptionalInt;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.ticketing.system.identity.application.service.AuthenticationService;
import com.ticketing.system.shared.dto.AuthTokenDTO;
import com.ticketing.system.shared.dto.GuestSessionDTO;
import com.ticketing.system.ui.session.AuthSession;
import com.ticketing.system.ui.session.FreshGuestSession;
import com.ticketing.system.ui.session.GuestSession;
import com.ticketing.system.ui.session.MapBackedVaadinSession;
import com.ticketing.system.ui.session.SessionIdentity;
import com.ticketing.system.ui.views.account.MyAccountView;
import com.ticketing.system.ui.views.admin.AdminDashboardView;
import com.ticketing.system.ui.views.auth.LoginView;
import com.ticketing.system.ui.views.company.MyCompaniesView;
import com.ticketing.system.ui.session.UnreadAlertsSendBack;
import com.ticketing.system.notifications.application.port.in.INotificationService;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterListener;
import com.vaadin.flow.server.ServiceInitEvent;
import com.vaadin.flow.server.UIInitEvent;
import com.vaadin.flow.server.UIInitListener;
import com.vaadin.flow.server.VaadinService;

/**
 * {@link AuthBootstrap}'s navigation guard admits a visitor by the session's stored flags, so it must
 * first check that the stored token is still live, for its kind: a member through
 * {@link SessionIdentity#isMember()}, an admin through {@link AuthenticationService#liveAdminId}.
 * A token that expired or was revoked signs the visitor out ({@link FreshGuestSession#restore()})
 * before the auth and capability gates run, so a dead admin token keeps no admin capabilities and a
 * dead member token renders no member page.
 *
 * <p>The guard is driven as Vaadin drives it: the test registers the listener through
 * {@code serviceInit} and fires a before-enter event for the target view.
 */
class AuthBootstrapTest {

    private static final String ADMIN_TOKEN = "admin-jwt";
    private static final String MEMBER_TOKEN = "member-jwt";
    // Member and admin ids share one id space; the default admin is id 1.
    private static final int ADMIN_ID = 1;
    private static final int MEMBER_ID = 42;
    private static final String GUEST_SESSION_ID = "guest-sid";
    private static final String FRESH_GUEST_SESSION_ID = "fresh-guest-sid";

    private AuthenticationService authenticationService;
    private BeforeEnterListener guard;

    @BeforeEach
    void setUp() {
        MapBackedVaadinSession.install();
        GuestSession.setSessionId(GUEST_SESSION_ID);
        authenticationService = mock(AuthenticationService.class);
        when(authenticationService.startGuestSession())
                .thenReturn(new GuestSessionDTO(FRESH_GUEST_SESSION_ID, Instant.EPOCH));
        when(authenticationService.isLiveGuestSession(GUEST_SESSION_ID)).thenReturn(true);
        FreshGuestSession freshGuestSession = new FreshGuestSession(authenticationService,
                new UnreadAlertsSendBack(mock(INotificationService.class)));
        SessionIdentity sessionIdentity = new SessionIdentity(authenticationService, freshGuestSession);
        guard = registeredGuard(new AuthBootstrap(sessionIdentity, authenticationService, freshGuestSession));
    }

    @AfterEach
    void tearDown() {
        MapBackedVaadinSession.uninstall();
    }

    // ---- an admin --------------------------------------------------------------------------

    @Test
    void GivenAdminWithADeadToken_WhenNavigatingToAnAdminView_ThenSignedOutAndForwardedToLogin() {
        storeSignedInAdmin();
        when(authenticationService.liveAdminId(ADMIN_TOKEN)).thenReturn(OptionalInt.empty());
        BeforeEnterEvent navigation = navigationTo(AdminDashboardView.class);

        guard.beforeEnter(navigation);

        verify(navigation).forwardTo(LoginView.class);
        assertFalse(AuthSession.isSignedIn(), "an admin whose token died must be signed out");
        assertFalse(AuthSession.isAdmin(), "an admin whose token died must keep no admin capabilities");
        assertNull(AuthSession.token(), "the dead admin token must be dropped");
        assertEquals(FRESH_GUEST_SESSION_ID, GuestSession.sessionId(), "the visitor continues as a fresh guest");
    }

    @Test
    void GivenAdminWithALiveToken_WhenNavigatingToAnAdminView_ThenAdmitted() {
        storeSignedInAdmin();
        when(authenticationService.liveAdminId(ADMIN_TOKEN)).thenReturn(OptionalInt.of(ADMIN_ID));
        BeforeEnterEvent navigation = navigationTo(AdminDashboardView.class);

        guard.beforeEnter(navigation);

        verify(navigation, never()).forwardTo(any(Class.class));
        assertTrue(AuthSession.isAdmin(), "a live admin stays signed in");
        assertEquals(ADMIN_TOKEN, AuthSession.token());
    }

    // An admin holds no member capability, and My companies itself needs one, so forwarding there
    // would forward the admin to the very view that refused them.
    @Test
    void GivenSignedInAdmin_WhenEnteringMyCompanies_ThenForwardedToTheAdminDashboard() {
        storeSignedInAdmin();
        when(authenticationService.liveAdminId(ADMIN_TOKEN)).thenReturn(OptionalInt.of(ADMIN_ID));
        BeforeEnterEvent navigation = navigationTo(MyCompaniesView.class);

        guard.beforeEnter(navigation);

        verify(navigation).forwardTo(AdminDashboardView.class);
    }

    // ---- a member --------------------------------------------------------------------------

    @Test
    void GivenMemberWithADeadToken_WhenNavigatingToAMemberView_ThenSignedOutAndForwardedToLogin() {
        storeSignedInMember();
        when(authenticationService.liveMemberId(MEMBER_TOKEN)).thenReturn(OptionalInt.empty());
        BeforeEnterEvent navigation = navigationTo(MyAccountView.class);

        guard.beforeEnter(navigation);

        verify(navigation).forwardTo(LoginView.class);
        assertFalse(AuthSession.isSignedIn(), "a member whose token died must be signed out");
        assertNull(AuthSession.token(), "the dead member token must be dropped");
    }

    @Test
    void GivenMemberWithALiveToken_WhenNavigatingToAMemberView_ThenAdmitted() {
        storeSignedInMember();
        when(authenticationService.liveMemberId(MEMBER_TOKEN)).thenReturn(OptionalInt.of(MEMBER_ID));
        BeforeEnterEvent navigation = navigationTo(MyAccountView.class);

        guard.beforeEnter(navigation);

        verify(navigation, never()).forwardTo(any(Class.class));
        assertTrue(AuthSession.isSignedIn(), "a live member stays signed in");
    }

    // ---- a guest ---------------------------------------------------------------------------

    @Test
    void GivenGuest_WhenNavigatingToAMemberView_ThenForwardedToLogin() {
        BeforeEnterEvent navigation = navigationTo(MyAccountView.class);

        guard.beforeEnter(navigation);

        verify(navigation).forwardTo(LoginView.class);
    }

    // ---- arrangement -------------------------------------------------------------------------

    private static BeforeEnterListener registeredGuard(AuthBootstrap bootstrap) {
        VaadinService vaadinService = mock(VaadinService.class);
        ServiceInitEvent initEvent = mock(ServiceInitEvent.class);
        when(initEvent.getSource()).thenReturn(vaadinService);
        bootstrap.serviceInit(initEvent);

        ArgumentCaptor<UIInitListener> uiInitListener = ArgumentCaptor.forClass(UIInitListener.class);
        verify(vaadinService).addUIInitListener(uiInitListener.capture());
        UI ui = mock(UI.class);
        UIInitEvent uiInitEvent = mock(UIInitEvent.class);
        when(uiInitEvent.getUI()).thenReturn(ui);
        uiInitListener.getValue().uiInit(uiInitEvent);

        ArgumentCaptor<BeforeEnterListener> beforeEnter = ArgumentCaptor.forClass(BeforeEnterListener.class);
        verify(ui).addBeforeEnterListener(beforeEnter.capture());
        return beforeEnter.getValue();
    }

    private static BeforeEnterEvent navigationTo(Class<?> target) {
        BeforeEnterEvent event = mock(BeforeEnterEvent.class);
        doReturn(target).when(event).getNavigationTarget();
        return event;
    }

    private static void storeSignedInAdmin() {
        AuthSession.storeAuth(new AuthTokenDTO(ADMIN_TOKEN, 0L, ADMIN_ID, "admin", true));
    }

    private static void storeSignedInMember() {
        AuthSession.storeAuth(new AuthTokenDTO(MEMBER_TOKEN, 0L, MEMBER_ID, "member"));
    }
}
