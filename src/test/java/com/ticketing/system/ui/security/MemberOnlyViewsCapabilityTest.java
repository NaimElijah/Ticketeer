package com.ticketing.system.ui.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

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
import com.ticketing.system.ui.views.account.MyProfileView;
import com.ticketing.system.ui.views.account.ReceiptView;
import com.ticketing.system.ui.views.account.SupportInboxView;
import com.ticketing.system.ui.views.admin.AdminDashboardView;
import com.ticketing.system.ui.views.company.CompanyRegistrationView;
import com.ticketing.system.ui.views.company.MyCompaniesView;
import com.ticketing.system.ui.views.messaging.NewInquiryView;
import com.ticketing.system.ui.views.messaging.SubmitComplaintView;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterListener;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.ServiceInitEvent;
import com.vaadin.flow.server.UIInitEvent;
import com.vaadin.flow.server.UIInitListener;
import com.vaadin.flow.server.VaadinService;
import com.vaadin.flow.server.auth.AnonymousAllowed;

/**
 * A member's own pages need a member capability, so {@link AuthBootstrap}'s capability gate turns a
 * signed-in admin away from them, as it does from every other member page (#187, UI-A8). A live member
 * is still admitted. The guard is driven as {@code AuthBootstrapTest} drives it.
 *
 * <p>The member views are found, not listed (#189): every routed view that needs a sign-in and no workspace or
 * admin capability, so a new member view is checked as soon as it exists, and one that declares no
 * capability, which would admit an admin, fails here.
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
    @MethodSource("memberViews")
    void GivenASignedInAdmin_WhenNavigatingToAMemberOnlyView_ThenForwardedToTheAdminDashboard(Class<?> memberView) {
        AuthSession.storeAuth(new AuthTokenDTO(ADMIN_TOKEN, 0L, ADMIN_ID, "admin", true));
        BeforeEnterEvent navigation = navigationTo(memberView);

        guard.beforeEnter(navigation);

        verify(navigation).forwardTo(AdminDashboardView.class);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("memberViews")
    void GivenALiveMemberWithNoCompany_WhenNavigatingToAMemberOnlyView_ThenAdmitted(Class<?> memberView) {
        AuthSession.storeAuth(new AuthTokenDTO(MEMBER_TOKEN, 0L, MEMBER_ID, "alice"));
        BeforeEnterEvent navigation = navigationTo(memberView);

        guard.beforeEnter(navigation);

        verify(navigation, never()).forwardTo(any(Class.class));
    }

    // An empty or partial scan would make the two tests above check nothing, so the views known today anchor it.
    @Test
    void GivenTheRoutedViews_WhenTheMemberViewsAreFound_ThenEveryMemberViewKnownTodayIsAmongThem() {
        Set<Class<?>> found = memberViews().collect(Collectors.toSet());

        Set<Class<?>> knownToday = Set.of(MyAccountView.class, MyProfileView.class, MyInvitationsView.class,
            MyCompaniesView.class, ReceiptView.class, SupportInboxView.class, NewInquiryView.class,
            SubmitComplaintView.class, CompanyRegistrationView.class);
        assertTrue(found.containsAll(knownToday), "the scan missed some: found " + names(found));
    }

    @Test
    void GivenTheRoutedViews_WhenOneNeedsASignIn_ThenItDeclaresTheCapabilityItNeeds() {
        Set<Class<?>> withoutCapability = routedViews()
            .filter(view -> !view.isAnnotationPresent(AnonymousAllowed.class))
            .filter(view -> !view.isAnnotationPresent(RequireCapability.class))
            .collect(Collectors.toSet());

        assertEquals(Set.of(), withoutCapability, "a view that needs only a sign-in admits a signed-in admin");
    }

    // Every routed view that needs a sign-in and neither a workspace nor an admin capability.
    static Stream<Class<?>> memberViews() {
        return routedViews()
            .filter(view -> !view.isAnnotationPresent(AnonymousAllowed.class))
            .filter(view -> {
                RequireCapability capability = view.getAnnotation(RequireCapability.class);
                return capability == null || !Capabilities.ADMIN_BUNDLE.contains(capability.value())
                    && !Capabilities.WORKSPACE_CAPABILITIES.contains(capability.value());
            });
    }

    private static Stream<Class<?>> routedViews() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Route.class));
        return scanner.findCandidateComponents("com.ticketing.system.ui").stream()
            .map(definition -> {
                try {
                    return (Class<?>) Class.forName(definition.getBeanClassName());
                } catch (ClassNotFoundException e) {
                    throw new IllegalStateException("a scanned view did not load: " + definition.getBeanClassName(), e);
                }
            });
    }

    private static List<String> names(Set<Class<?>> views) {
        return views.stream().map(Class::getSimpleName).sorted().toList();
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
