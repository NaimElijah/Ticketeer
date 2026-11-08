package com.ticketing.system.unit.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ticketing.system.shared.dto.AuthTokenDTO;
import com.ticketing.system.shared.dto.GuestSessionDTO;
import com.ticketing.system.shared.dto.LogoutRequestDTO;
import com.ticketing.system.identity.application.service.AuthenticationService;
import com.ticketing.system.ui.security.SignOutFlow;
import com.ticketing.system.ui.session.AuthSession;
import com.ticketing.system.ui.session.FreshGuestSession;
import com.ticketing.system.ui.session.GuestSession;
import com.ticketing.system.ui.session.MapBackedVaadinSession;
import com.ticketing.system.testutil.LogCapture;
import com.ticketing.system.ui.session.UnreadAlertsSendBack;
import com.ticketing.system.notifications.application.port.in.INotificationService;

import ch.qos.logback.classic.Level;

import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * {@link SignOutFlow#execute()} revokes the visitor's token, then leaves the Vaadin session signed out with
 * a fresh guest session id. The session state is arranged and read through {@link AuthSession} and
 * {@link GuestSession} on a map-backed session, never through their attribute keys (#189).
 */
class SignOutFlowTest {

    private static final Instant GUEST_SESSION_CREATED_AT = Instant.parse("2027-07-15T17:00:00Z");
    private static final String FRESH_GUEST_ID = "fresh-sid";
    private static final String MEMBER_TOKEN = "jwt-abc";
    // Login promotes the guest session in place, so a signed-in member's guest id is their own session.
    private static final String PROMOTED_SESSION_ID = "member-sid";
    private static final int MEMBER_ID = 42;

    private AuthenticationService authenticationService;
    private SignOutFlow signOutFlow;
    private LogCapture logs;

    @BeforeEach
    void setUp() {
        authenticationService = mock(AuthenticationService.class);
        signOutFlow = new SignOutFlow(authenticationService, new FreshGuestSession(authenticationService,
                new UnreadAlertsSendBack(mock(INotificationService.class))));
        when(authenticationService.startGuestSession())
            .thenReturn(new GuestSessionDTO(FRESH_GUEST_ID, GUEST_SESSION_CREATED_AT));
    }

    @AfterEach
    void tearDown() {
        MapBackedVaadinSession.uninstall();
        if (logs != null) {
            logs.close();
        }
    }

    @Test
    void GivenNoVaadinSession_WhenExecute_ThenCallsStartGuestSessionAndSkipsLogout() {
        signOutFlow.execute();

        verify(authenticationService).startGuestSession();
        verify(authenticationService, never()).logout(any());
    }

    @Test
    void GivenATokenPresent_WhenExecute_ThenCallsLogoutWithThatTokenThenMintsGuest() {
        signedInMember(MEMBER_TOKEN);

        signOutFlow.execute();

        InOrder order = inOrder(authenticationService);
        order.verify(authenticationService).logout(new LogoutRequestDTO(MEMBER_TOKEN));
        order.verify(authenticationService).startGuestSession();
    }

    @Test
    void GivenASessionWithNoToken_WhenExecute_ThenSkipsLogoutAndStillMintsGuest() {
        MapBackedVaadinSession.install();

        signOutFlow.execute();

        verify(authenticationService, never()).logout(any(LogoutRequestDTO.class));
        verify(authenticationService).startGuestSession();
    }

    @Test
    void GivenABlankToken_WhenExecute_ThenSkipsLogoutAndStillMintsGuest() {
        signedInMember("   ");

        signOutFlow.execute();

        verify(authenticationService, never()).logout(any(LogoutRequestDTO.class));
        verify(authenticationService).startGuestSession();
    }

    @Test
    void GivenAMemberWhoseGuestIdIsTheirOwnSession_WhenExecute_ThenTheFreshGuestSessionIdIsPinned() {
        signedInMember(MEMBER_TOKEN);

        signOutFlow.execute();

        assertEquals(FRESH_GUEST_ID, GuestSession.sessionId());
    }

    @Test
    void GivenASignedInAdmin_WhenExecute_ThenNoTokenNoSignInAndNoAdminRemain() {
        MapBackedVaadinSession.install();
        AuthSession.storeAuth(new AuthTokenDTO("admin-jwt", 0L, 1, "admin", true));

        signOutFlow.execute();

        assertNull(AuthSession.token(), "the token is gone");
        assertFalse(AuthSession.isSignedIn(), "the visitor is signed out");
        assertFalse(AuthSession.isAdmin(), "the visitor is no admin any more");
    }

    // FreshGuestSession.restore never leaves the member's own session pinned as the guest id.
    @Test
    void GivenStartGuestSessionFails_WhenExecute_ThenTheMembersSessionIdIsClearedAndNothingIsThrown() {
        signedInMember(MEMBER_TOKEN);
        when(authenticationService.startGuestSession())
            .thenThrow(new IllegalStateException("session store down"));

        assertDoesNotThrow(() -> signOutFlow.execute());

        assertNull(GuestSession.sessionId(), "the member's promoted session id must not stay pinned");
        assertFalse(AuthSession.isSignedIn(), "the visitor is signed out even without a new guest session");
    }

    @Test
    void GivenLogoutFails_WhenExecute_ThenDoesNotThrowAndStillMintsGuest() {
        signedInMember(MEMBER_TOKEN);
        doThrow(new IllegalStateException("downstream blew up"))
            .when(authenticationService).logout(any());

        assertDoesNotThrow(() -> signOutFlow.execute());
        // Still mints the new guest so the next register/login can proceed.
        verify(authenticationService).startGuestSession();
        assertEquals(FRESH_GUEST_ID, GuestSession.sessionId());
    }

    // #187: a failed logout may leave the session, and so the token, live while the user sees a
    // sign-out. That is actionable, so it is an ERROR, with the exception for its cause and stack.
    @Test
    void GivenLogoutFails_WhenExecute_ThenAnErrorIsLoggedWithTheExceptionAttached() {
        signedInMember(MEMBER_TOKEN);
        doThrow(new IllegalStateException("session store unreachable"))
            .when(authenticationService).logout(any());
        logs = LogCapture.of(SignOutFlow.class);

        signOutFlow.execute();

        assertThat(logs.throwableClassNamesAt(Level.ERROR))
            .as("the failed logout must be logged at ERROR with the exception attached")
            .containsExactly(IllegalStateException.class.getName());
    }

    private static void signedInMember(String token) {
        MapBackedVaadinSession.install();
        GuestSession.setSessionId(PROMOTED_SESSION_ID);
        AuthSession.storeAuth(new AuthTokenDTO(token, 0L, MEMBER_ID, "member"));
    }
}
