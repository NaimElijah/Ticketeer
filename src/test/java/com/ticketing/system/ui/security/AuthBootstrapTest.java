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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.times;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import com.ticketing.system.organization.application.dto.MembershipRole;
import com.ticketing.system.organization.application.dto.UserCompanyDTO;
import com.ticketing.system.organization.application.service.CompanyManagementService;
import com.ticketing.system.organization.domain.Permission;
import com.ticketing.system.ui.session.CompanyManagementBridge;
import com.ticketing.system.ui.views.auth.AdminLoginView;
import com.ticketing.system.ui.views.company.OwnerDashboardView;
import com.vaadin.flow.internal.CurrentInstance;
import com.vaadin.flow.server.VaadinRequest;

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

    // Signed out, the visitor is sent to sign in where an admin can: the admin sign-in (UI-B2).
    @Test
    void GivenAdminWithADeadToken_WhenNavigatingToAnAdminView_ThenSignedOutAndForwardedToTheAdminLogin() {
        storeSignedInAdmin();
        when(authenticationService.liveAdminId(ADMIN_TOKEN)).thenReturn(OptionalInt.empty());
        BeforeEnterEvent navigation = navigationTo(AdminDashboardView.class);

        guard.beforeEnter(navigation);

        verify(navigation).forwardTo(AdminLoginView.class);
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

    // ---- UI-B2: an admin route sends the visitor to the admin sign-in (#187) ----
    // A member route still sends a signed-out visitor to the member sign-in:
    // GivenGuest_WhenNavigatingToAMemberView_ThenForwardedToLogin above.

    @Test
    void GivenSignedOutVisitor_WhenNavigatingToAnAdminView_ThenForwardedToTheAdminLogin() {
        BeforeEnterEvent navigation = navigationTo(AdminDashboardView.class);

        guard.beforeEnter(navigation);

        verify(navigation).forwardTo(AdminLoginView.class);
    }

    // The member sign-in counts its attempts against the member lockout pool, so an admin route must
    // never send anyone there.
    @Test
    void GivenSignedInMember_WhenNavigatingToAnAdminView_ThenForwardedToTheAdminLogin() {
        storeSignedInMember();
        when(authenticationService.liveMemberId(MEMBER_TOKEN)).thenReturn(OptionalInt.of(MEMBER_ID));
        bridgeMemberships(List.of());
        BeforeEnterEvent navigation = navigationTo(AdminDashboardView.class);

        guard.beforeEnter(navigation);

        verify(navigation).forwardTo(AdminLoginView.class);
    }

    // ---- UI-B4: capabilities are resolved once per navigation (#187) ----
    // One navigation is one request and one pass of the guard; the view it lands on then asks for
    // several capabilities, as "My Events" asks per row.

    @Test
    void GivenAFounderOnOneNavigation_WhenSeveralCapabilitiesAreChecked_ThenTheMembershipsAreReadOnce() {
        signInAsLiveFounder();

        navigateAndRenderMyEvents();

        verify(companyManagementService, times(1)).listForUser(MEMBER_TOKEN);
    }

    @Test
    void GivenAFounder_WhenASecondNavigationChecksCapabilities_ThenTheMembershipsAreReadAgain() {
        signInAsLiveFounder();
        navigateAndRenderMyEvents();

        navigateAndRenderMyEvents();

        verify(companyManagementService, times(2)).listForUser(MEMBER_TOKEN);
    }

    // A click that registers a company and then navigates to the workspace is one request: the read
    // made before the registration must not decide the navigation.
    @Test
    void GivenAMemberWhoRegistersACompanyAndNavigatesInOneRequest_WhenTheGuardRuns_ThenTheNewCompanyAdmitsThem() {
        storeSignedInMember();
        when(authenticationService.liveMemberId(MEMBER_TOKEN)).thenReturn(OptionalInt.of(MEMBER_ID));
        bridgeMemberships(List.of());
        currentRequest = mapBackedRequest();
        CurrentInstance.set(VaadinRequest.class, currentRequest);
        assertFalse(Capabilities.has(Capability.VIEW_COMPANY_EVENTS), "precondition: no company yet");
        bridgeMemberships(List.of(membership(MembershipRole.FOUNDER, List.of())));
        BeforeEnterEvent navigation = navigationTo(ViewCompanyEventsRoute.class);

        guard.beforeEnter(navigation);

        verify(navigation, never()).forwardTo(any(Class.class));
    }

    private void signInAsLiveFounder() {
        storeSignedInMember();
        when(authenticationService.liveMemberId(MEMBER_TOKEN)).thenReturn(OptionalInt.of(MEMBER_ID));
        bridgeMemberships(List.of(membership(MembershipRole.FOUNDER, List.of())));
    }

    private void navigateAndRenderMyEvents() {
        currentRequest = mapBackedRequest();
        CurrentInstance.set(VaadinRequest.class, currentRequest);
        BeforeEnterEvent navigation = navigationTo(ViewCompanyEventsRoute.class);

        guard.beforeEnter(navigation);
        verify(navigation, never()).forwardTo(any(Class.class));
        Capabilities.has(Capability.EDIT_COMPANY_EVENTS);
        Capabilities.has(Capability.CANCEL_EVENT);
        Capabilities.hasAny(Capability.VIEW_COMPANY_SALES, Capability.EDIT_PURCHASE_POLICIES);
    }

    // ---- UI-B7: every workspace capability gets the workspace fallback (#187) ----
    // A company member who lacks a workspace capability lands on the workspace home with a toast, not
    // on "My companies". The workspace capabilities are derived from Capabilities itself: what a founder
    // holds beyond a member of no company.

    @Test
    void GivenAManagerWithoutPermissions_WhenNavigatingToAnyWorkspaceRoute_ThenForwardedToTheOwnerDashboard() {
        storeSignedInMember();
        when(authenticationService.liveMemberId(MEMBER_TOKEN)).thenReturn(OptionalInt.of(MEMBER_ID));
        Set<Capability> workspaceCapabilities = capabilitiesAFounderHoldsBeyondAMemberOfNoCompany();
        bridgeMemberships(List.of(membership(MembershipRole.MANAGER, List.of())));
        workspaceCapabilities.removeAll(Capabilities.forCurrentUser());
        assertFalse(workspaceCapabilities.isEmpty(), "precondition: a manager without permissions lacks them");

        Map<Capability, String> wrongFallbacks = new EnumMap<>(Capability.class);
        for (Capability capability : workspaceCapabilities) {
            Class<?> route = WORKSPACE_ROUTES.get(capability);
            assertNotNull(route, "add a fixture route requiring " + capability + " to WORKSPACE_ROUTES");
            BeforeEnterEvent navigation = navigationTo(route);

            guard.beforeEnter(navigation);

            Class<?> forwardedTo = forwardedTo(navigation);
            if (forwardedTo != OwnerDashboardView.class) {
                wrongFallbacks.put(capability, forwardedTo == null ? "admitted" : forwardedTo.getSimpleName());
            }
        }
        assertEquals(Map.of(), wrongFallbacks,
                "each workspace capability must forward a member who lacks it to OwnerDashboardView");
    }

    private Set<Capability> capabilitiesAFounderHoldsBeyondAMemberOfNoCompany() {
        bridgeMemberships(List.of());
        Set<Capability> memberOfNoCompany = Capabilities.forCurrentUser();
        bridgeMemberships(List.of(membership(MembershipRole.FOUNDER, List.of())));
        Set<Capability> founder = EnumSet.copyOf(Capabilities.forCurrentUser());
        founder.removeAll(memberOfNoCompany);
        return founder;
    }

    private static Class<?> forwardedTo(BeforeEnterEvent navigation) {
        return mockingDetails(navigation).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("forwardTo"))
                .map(invocation -> invocation.getArgument(0))
                .filter(Class.class::isInstance)
                .map(argument -> (Class<?>) argument)
                .findFirst()
                .orElse(null);
    }

    // One route per workspace capability, as a view would declare it.
    @RequireCapability(Capability.VIEW_COMPANY_EVENTS) private static final class ViewCompanyEventsRoute { }
    @RequireCapability(Capability.EDIT_COMPANY_EVENTS) private static final class EditCompanyEventsRoute { }
    @RequireCapability(Capability.VIEW_COMPANY_SALES) private static final class ViewCompanySalesRoute { }
    @RequireCapability(Capability.EDIT_PURCHASE_POLICIES) private static final class EditPurchasePoliciesRoute { }
    @RequireCapability(Capability.RESPOND_INQUIRIES) private static final class RespondInquiriesRoute { }
    @RequireCapability(Capability.MANAGE_VENUE_MAPS) private static final class ManageVenueMapsRoute { }
    @RequireCapability(Capability.CANCEL_EVENT) private static final class CancelEventRoute { }
    @RequireCapability(Capability.APPOINT_MANAGER) private static final class AppointManagerRoute { }
    @RequireCapability(Capability.APPOINT_CO_OWNER) private static final class AppointCoOwnerRoute { }
    @RequireCapability(Capability.EDIT_MANAGER_PERMISSIONS) private static final class EditManagerPermissionsRoute { }
    @RequireCapability(Capability.REVOKE_MANAGER) private static final class RevokeManagerRoute { }
    @RequireCapability(Capability.VIEW_COMPANY_ORG_TREE) private static final class ViewCompanyOrgTreeRoute { }
    @RequireCapability(Capability.DISSOLVE_COMPANY) private static final class DissolveCompanyRoute { }
    @RequireCapability(Capability.TRANSFER_FOUNDERSHIP) private static final class TransferFoundershipRoute { }
    @RequireCapability(Capability.OWNER_WORKSPACE) private static final class OwnerWorkspaceRoute { }

    private static final Map<Capability, Class<?>> WORKSPACE_ROUTES = Stream.of(
            ViewCompanyEventsRoute.class, EditCompanyEventsRoute.class, ViewCompanySalesRoute.class,
            EditPurchasePoliciesRoute.class, RespondInquiriesRoute.class, ManageVenueMapsRoute.class,
            CancelEventRoute.class, AppointManagerRoute.class, AppointCoOwnerRoute.class,
            EditManagerPermissionsRoute.class, RevokeManagerRoute.class, ViewCompanyOrgTreeRoute.class,
            DissolveCompanyRoute.class, TransferFoundershipRoute.class, OwnerWorkspaceRoute.class)
        .collect(Collectors.toMap(route -> route.getAnnotation(RequireCapability.class).value(), route -> route));

    // ---- fixture shared by the UI-B2, UI-B4 and UI-B7 blocks: the member's companies, read through ----
    // ---- CompanyManagementBridge, and the current request                                          ----

    private static final int COMPANY_ID = 7;

    private CompanyManagementService companyManagementService;
    // The bridge is static and a Spring context in the same JVM may have set it, so it is restored after.
    private CompanyManagementService bridgedBefore;
    // Vaadin holds the current request through a weak reference, so the test keeps it reachable.
    private VaadinRequest currentRequest;

    @AfterEach
    void restoreTheBridgeAndTheRequest() {
        if (companyManagementService != null) {
            new CompanyManagementBridge(bridgedBefore);
        }
        CurrentInstance.set(VaadinRequest.class, null);
        currentRequest = null;
    }

    private void bridgeMemberships(List<UserCompanyDTO> memberships) {
        if (companyManagementService == null) {
            bridgedBefore = CompanyManagementBridge.service();
            companyManagementService = mock(CompanyManagementService.class);
            new CompanyManagementBridge(companyManagementService);
        }
        when(companyManagementService.listForUser(MEMBER_TOKEN)).thenReturn(memberships);
    }

    private static UserCompanyDTO membership(MembershipRole role, List<Permission> managerPermissions) {
        return new UserCompanyDTO(COMPANY_ID, "Acme", "desc", "", role, "ACTIVE", 1, 0, managerPermissions);
    }

    private static VaadinRequest mapBackedRequest() {
        Map<String, Object> attributes = new HashMap<>();
        VaadinRequest request = mock(VaadinRequest.class);
        when(request.getAttribute(anyString())).thenAnswer(call -> attributes.get(call.getArgument(0, String.class)));
        doAnswer(call -> attributes.put(call.getArgument(0), call.getArgument(1)))
                .when(request).setAttribute(anyString(), any());
        doAnswer(call -> attributes.remove(call.getArgument(0, String.class)))
                .when(request).removeAttribute(anyString());
        return request;
    }
}
