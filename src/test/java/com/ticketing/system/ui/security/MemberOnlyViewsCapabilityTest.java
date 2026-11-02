package com.ticketing.system.ui.security;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.OptionalInt;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import com.ticketing.system.identity.application.service.AuthenticationService;
import com.ticketing.system.notifications.application.port.in.INotificationService;
import com.ticketing.system.organization.application.service.CompanyManagementService;
import com.ticketing.system.shared.dto.AuthTokenDTO;
import com.ticketing.system.shared.dto.GuestSessionDTO;
import com.ticketing.system.ui.session.AuthSession;
import com.ticketing.system.ui.session.CompanyManagementBridge;
import com.ticketing.system.ui.session.FreshGuestSession;
import com.ticketing.system.ui.session.GuestSession;
import com.ticketing.system.ui.session.MapBackedVaadinSession;
import com.ticketing.system.ui.session.SessionIdentity;
import com.ticketing.system.ui.session.UnreadAlertsSendBack;
import com.ticketing.system.ui.views.account.MyAccountView;
import com.ticketing.system.ui.views.account.MyInvitationsView;
import com.ticketing.system.ui.views.account.ReceiptView;
import com.ticketing.system.ui.views.account.SupportInboxView;
import com.ticketing.system.ui.views.admin.AdminDashboardView;
import com.ticketing.system.ui.views.company.CompanyRegistrationView;
import com.ticketing.system.ui.views.messaging.NewInquiryView;
import com.ticketing.system.ui.views.messaging.SubmitComplaintView;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterListener;
import com.vaadin.flow.server.ServiceInitEvent;
import com.vaadin.flow.server.UIInitEvent;
import com.vaadin.flow.server.UIInitListener;
import com.vaadin.flow.server.VaadinService;

/**
 * A member's own pages need a member capability, so {@link AuthBootstrap}'s capability gate turns a
 * signed-in admin away from them, as it does from every other member page (#187, UI-A8). A live member
 * is still admitted. The guard is driven as {@code AuthBootstrapTest} drives it.
 */
class MemberOnlyViewsCapabilityTest {

    // ---- UI-A8: member-only views lack a capability (#187) ----

    private static final String ADMIN_TOKEN = "admin-jwt";
    private static final String MEMBER_TOKEN = "member-jwt";
    private static final int ADMIN_ID = 1;
    private static final int MEMBER_ID = 42;
    private static final String GUEST_SESSION_ID = "guest-sid";

    private AuthenticationService authenticationService;
    private BeforeEnterListener guard;
    private CompanyManagementService bridgedBefore;

    @BeforeEach
    void setUp() {
        MapBackedVaadinSession.install();
        GuestSession.setSessionId(GUEST_SESSION_ID);
        authenticationService = mock(AuthenticationService.class);
        when(authenticationService.startGuestSession()).thenReturn(new GuestSessionDTO("fresh-sid", Instant.EPOCH));
        when(authenticationService.isLiveGuestSession(anyString())).thenReturn(true);
        when(authenticationService.liveAdminId(ADMIN_TOKEN)).thenReturn(OptionalInt.of(ADMIN_ID));
        when(authenticationService.liveMemberId(MEMBER_TOKEN)).thenReturn(OptionalInt.of(MEMBER_ID));
        bridgedBefore = CompanyManagementBridge.service();
        CompanyManagementService companies = mock(CompanyManagementService.class);
        when(companies.listForUser(MEMBER_TOKEN)).thenReturn(List.of());
        new CompanyManagementBridge(companies);
        FreshGuestSession freshGuestSession = new FreshGuestSession(authenticationService,
                new UnreadAlertsSendBack(mock(INotificationService.class)));
        SessionIdentity sessionIdentity = new SessionIdentity(authenticationService, freshGuestSession);
        guard = registeredGuard(new AuthBootstrap(sessionIdentity, authenticationService, freshGuestSession));
    }

    @AfterEach
    void tearDown() {
        new CompanyManagementBridge(bridgedBefore);
        MapBackedVaadinSession.uninstall();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(classes = {MyAccountView.class, MyInvitationsView.class, ReceiptView.class, SupportInboxView.class,
        NewInquiryView.class, SubmitComplaintView.class, CompanyRegistrationView.class})
    void GivenASignedInAdmin_WhenNavigatingToAMemberOnlyView_ThenForwardedToTheAdminDashboard(Class<?> memberView) {
        AuthSession.storeAuth(new AuthTokenDTO(ADMIN_TOKEN, 0L, ADMIN_ID, "admin", true));
        BeforeEnterEvent navigation = navigationTo(memberView);

        guard.beforeEnter(navigation);

        verify(navigation).forwardTo(AdminDashboardView.class);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(classes = {MyAccountView.class, MyInvitationsView.class, ReceiptView.class, SupportInboxView.class,
        NewInquiryView.class, SubmitComplaintView.class, CompanyRegistrationView.class})
    void GivenALiveMemberWithNoCompany_WhenNavigatingToAMemberOnlyView_ThenAdmitted(Class<?> memberView) {
        AuthSession.storeAuth(new AuthTokenDTO(MEMBER_TOKEN, 0L, MEMBER_ID, "alice"));
        BeforeEnterEvent navigation = navigationTo(memberView);

        guard.beforeEnter(navigation);

        verify(navigation, never()).forwardTo(any(Class.class));
    }

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
}
