package com.ticketing.system.unit.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;

import com.ticketing.system.identity.application.service.AuthenticationService;
import com.ticketing.system.organization.application.service.CompanyManagementService;
import com.ticketing.system.sales.application.service.ReservationService;
import com.ticketing.system.notifications.application.port.in.INotificationService;
import com.ticketing.system.shared.dto.AuthTokenDTO;
import com.ticketing.system.shared.dto.GuestSessionDTO;
import com.ticketing.system.shared.dto.LoginDTO;
import com.ticketing.system.shared.dto.LoginRequestDTO;
import com.ticketing.system.testutil.LogCapture;
import com.ticketing.system.ui.dev.DevPanel;
import com.ticketing.system.ui.security.SignOutFlow;
import com.ticketing.system.ui.session.AuthSession;
import com.ticketing.system.ui.session.CartBadgeCache;
import com.ticketing.system.ui.session.CompanyManagementBridge;
import com.ticketing.system.ui.session.FreshGuestSession;
import com.ticketing.system.ui.session.GuestSession;
import com.ticketing.system.ui.session.MapBackedVaadinSession;
import com.ticketing.system.ui.session.SessionIdentity;
import com.ticketing.system.ui.session.UnreadAlertsSendBack;
import com.ticketing.system.ui.support.ServiceErrors;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.dialog.Dialog;

import ch.qos.logback.classic.Level;
import jakarta.persistence.PersistenceException;

/**
 * The dev panel opened headless on {@link InboxViewHarness}'s UI, its persona toggles clicked through
 * Vaadin's own event path. Its services are test doubles of the project's own: the reservations, the
 * identity service, the sign-out flow and the company service behind {@link CompanyManagementBridge}.
 */
class DevPanelTest {

    private static final String MEMBER_TOKEN = "member-jwt";
    private static final int MEMBER_ID = 42;
    private static final String ADMIN_TOKEN = "admin-jwt";
    // Member and admin ids share one id space (ADR-012).
    private static final int ADMIN_ID = 42;
    private static final String GUEST_SESSION_ID = "guest-sid";
    private static final String FRESH_GUEST_SESSION_ID = "fresh-guest-sid";
    private static final String DEFAULT_ADMIN_USERNAME = "admin";
    private static final String DEFAULT_ADMIN_PASSWORD = "admin";
    private static final long BADGE_READ_AT_MS = 1_000_000L;

    private InboxViewHarness harness;
    private ReservationService reservationService;
    private AuthenticationService authenticationService;
    // The bridge is static and a Spring context in the same JVM may have set it, so it is restored after.
    private CompanyManagementService bridgedBefore;
    private LogCapture serviceErrorsLog;

    @BeforeEach
    void setUp() {
        MapBackedVaadinSession.install();
        GuestSession.setSessionId(GUEST_SESSION_ID);
        harness = new InboxViewHarness();
        reservationService = mock(ReservationService.class);
        authenticationService = mock(AuthenticationService.class);
        DevPanel.init(reservationService);
        DevPanel.bindBeans(authenticationService, mock(SignOutFlow.class),
                new SessionIdentity(authenticationService, new FreshGuestSession(authenticationService,
                        new UnreadAlertsSendBack(mock(INotificationService.class)))),
                DEFAULT_ADMIN_USERNAME, DEFAULT_ADMIN_PASSWORD);
        bridgedBefore = CompanyManagementBridge.service();
        CompanyManagementService companies = mock(CompanyManagementService.class);
        when(companies.listForUser(anyString())).thenReturn(List.of());
        new CompanyManagementBridge(companies);
        serviceErrorsLog = LogCapture.of(ServiceErrors.class);
    }

    @AfterEach
    void tearDown() {
        serviceErrorsLog.close();
        new CompanyManagementBridge(bridgedBefore);
        DevPanel.init(null);
        DevPanel.bindBeans(null, null, null, DEFAULT_ADMIN_USERNAME, DEFAULT_ADMIN_PASSWORD);
        harness.close();
        MapBackedVaadinSession.uninstall();
    }

    // ---- UI-B14: the dev panel's failures go through ServiceErrors, and an admin is no member (#187) ----

    @Test
    void GivenSignInFailsInternally_WhenTheMemberPersonaIsClicked_ThenServiceErrorsLogsTheFailure() {
        when(authenticationService.login(any())).thenThrow(new PersistenceException("relation \"users\" missing"));

        clickPersona("Member");

        assertEquals(List.of(PersistenceException.class.getName()), serviceErrorsLog.throwableClassNamesAt(Level.ERROR),
                "a failed sign-in must not be swallowed; ServiceErrors must log it with its exception");
    }

    @Test
    void GivenAdminSignInFailsInternally_WhenTheAdminPersonaIsClicked_ThenServiceErrorsLogsTheFailure() {
        when(authenticationService.signInAsAdmin(anyString(), anyString()))
                .thenThrow(new PersistenceException("relation \"admins\" missing"));

        clickPersona("Admin");

        assertEquals(List.of(PersistenceException.class.getName()), serviceErrorsLog.throwableClassNamesAt(Level.ERROR),
                "a failed admin sign-in must not be swallowed; ServiceErrors must log it with its exception");
    }

    // A member sign-in swaps the cart, as LoginView's does, so the badge's cache is dropped (#187).
    @Test
    void GivenACachedCartBadge_WhenTheMemberPersonaSignsIn_ThenTheBadgeIsReadAgain() {
        CartBadgeCache.store(new CartBadgeCache.Badge(2, null), BADGE_READ_AT_MS);
        when(authenticationService.login(any())).thenReturn(
            new LoginDTO(new AuthTokenDTO(MEMBER_TOKEN, 0L, MEMBER_ID, "member"), null, List.of()));

        clickPersona("Member");

        assertTrue(CartBadgeCache.fresh(BADGE_READ_AT_MS).isEmpty(), "the signed-in member's cart must be read again");
    }

    // The dev panel signs in on a live guest session id, as LoginView does: an expired one is replaced first.
    @Test
    void GivenTheGuestSessionExpired_WhenTheMemberPersonaSignsIn_ThenAFreshGuestSessionIdIsPassedOn() {
        when(authenticationService.isLiveGuestSession(GUEST_SESSION_ID)).thenReturn(false);
        when(authenticationService.isLiveGuestSession(FRESH_GUEST_SESSION_ID)).thenReturn(true);
        when(authenticationService.startGuestSession())
                .thenReturn(new GuestSessionDTO(FRESH_GUEST_SESSION_ID, Instant.EPOCH));
        when(authenticationService.login(any())).thenReturn(
                new LoginDTO(new AuthTokenDTO(MEMBER_TOKEN, 0L, MEMBER_ID, "member"), null, List.of()));

        clickPersona("Member");

        ArgumentCaptor<LoginRequestDTO> request = ArgumentCaptor.forClass(LoginRequestDTO.class);
        verify(authenticationService).login(request.capture());
        assertEquals(FRESH_GUEST_SESSION_ID, request.getValue().guestSessionId());
    }

    // An admin token is no member credential, so signing an admin out never asks for a member's cart.
    @Test
    void GivenASignedInAdmin_WhenTheGuestPersonaIsClicked_ThenNoMemberCartIsAskedForOrAbandoned() {
        AuthSession.storeAuth(new AuthTokenDTO(ADMIN_TOKEN, 0L, ADMIN_ID, "admin", true));

        clickPersona("Guest");

        verify(reservationService, never()).viewMyActiveOrder(ADMIN_TOKEN);
        verify(reservationService, never()).abandonActiveOrder(any());
    }

    // ---- UI-C2: a store failure from viewMyActiveOrder is shown through ServiceErrors (#187) ----

    @Test
    void GivenTheStoreIsDown_WhenASignedInMemberClicksTheGuestPersona_ThenTheOutageMessageIsShown() {
        AuthSession.storeAuth(new AuthTokenDTO(MEMBER_TOKEN, 0L, MEMBER_ID, "member"));
        when(reservationService.viewMyActiveOrder(MEMBER_TOKEN))
                .thenThrow(new DataAccessResourceFailureException("connection refused"));

        clickPersona("Guest");

        List<String> shown = shownTexts();
        assertTrue(shown.contains(ServiceErrors.DB_UNAVAILABLE_MESSAGE),
                "the store outage must be shown with ServiceErrors' text; shown: " + shown);
    }

    // ---- arrangement ------------------------------------------------------------------------------

    private void clickPersona(String persona) {
        DevPanel.show();
        harness.toasts();
        Button toggle = InboxViewHarness.find(UI.getCurrent(), Dialog.class).stream()
                .flatMap(dialog -> InboxViewHarness.find(dialog, Button.class).stream())
                .filter(button -> persona.equals(button.getText()))
                .findFirst()
                .orElse(null);
        assertNotNull(toggle, "precondition: the dev panel shows the " + persona + " persona toggle");
        InboxViewHarness.click(toggle);
    }

    // Every text the UI shows after the click: the toasts, and any text in the UI's components (dialogs too).
    private List<String> shownTexts() {
        List<String> shown = new ArrayList<>(harness.toasts());
        for (Component component : InboxViewHarness.find(UI.getCurrent(), Component.class)) {
            String text = component.getElement().getText();
            if (text != null && !text.isBlank()) {
                shown.add(text);
            }
        }
        return shown;
    }
}
