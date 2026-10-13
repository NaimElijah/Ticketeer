package com.ticketing.system.unit.presentation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ticketing.system.shared.dto.GuestSessionDTO;
import com.ticketing.system.shared.dto.LogoutRequestDTO;
import com.ticketing.system.identity.application.service.AuthenticationService;
import com.ticketing.system.ui.security.SignOutFlow;
import com.vaadin.flow.server.VaadinSession;

import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class SignOutFlowTest {

    private AuthenticationService authenticationService;
    private SignOutFlow signOutFlow;

    @BeforeEach
    void setUp() {
        authenticationService = mock(AuthenticationService.class);
        signOutFlow = new SignOutFlow(authenticationService);
        when(authenticationService.startGuestSession())
            .thenReturn(new GuestSessionDTO("fresh-sid", Instant.now()));
    }

    @AfterEach
    void tearDown() {
        VaadinSession.setCurrent(null);
    }

    @Test
    void execute_withoutVaadinSession_callsStartGuestSessionAndSkipsLogout() {
        signOutFlow.execute();

        verify(authenticationService).startGuestSession();
        verify(authenticationService, never()).logout(any());
    }

    @Test
    void execute_withTokenPresent_callsLogoutWithThatTokenThenMintsGuest() {
        VaadinSession session = mock(VaadinSession.class);
        when(session.getAttribute("authSession.token")).thenReturn("jwt-abc");
        VaadinSession.setCurrent(session);

        signOutFlow.execute();

        InOrder order = inOrder(authenticationService);
        order.verify(authenticationService).logout(argThat(req -> "jwt-abc".equals(req.token())));
        order.verify(authenticationService).startGuestSession();
    }

    @Test
    void execute_withNullToken_skipsLogout() {
        VaadinSession session = mock(VaadinSession.class);
        when(session.getAttribute("authSession.token")).thenReturn(null);
        VaadinSession.setCurrent(session);

        signOutFlow.execute();

        verify(authenticationService, never()).logout(any(LogoutRequestDTO.class));
        verify(authenticationService).startGuestSession();
    }

    @Test
    void execute_withBlankToken_skipsLogout() {
        VaadinSession session = mock(VaadinSession.class);
        when(session.getAttribute("authSession.token")).thenReturn("   ");
        VaadinSession.setCurrent(session);

        signOutFlow.execute();

        verify(authenticationService, never()).logout(any(LogoutRequestDTO.class));
        verify(authenticationService).startGuestSession();
    }

    @Test
    void execute_pinsFreshGuestSessionIdOnSession() {
        VaadinSession session = mock(VaadinSession.class);
        VaadinSession.setCurrent(session);

        signOutFlow.execute();

        verify(session).setAttribute(eq("guestSession.sessionId"), eq("fresh-sid"));
    }

    @Test
    void execute_clearsAuthSessionAttributes() {
        VaadinSession session = mock(VaadinSession.class);
        when(session.getAttribute("authSession.token")).thenReturn("jwt-abc");
        VaadinSession.setCurrent(session);

        signOutFlow.execute();

        // The Vaadin attributes that AuthSession.signOut() clears — token is
        // nulled, signedIn flips to false, admin flips to false.
        verify(session).setAttribute("authSession.token", null);
        verify(session).setAttribute("authSession.signedIn", Boolean.FALSE);
        verify(session).setAttribute("authSession.admin", Boolean.FALSE);
    }

    @Test
    void execute_doesNotThrowWhenStartGuestSessionFails() {
        when(authenticationService.startGuestSession())
            .thenThrow(new IllegalStateException("session store down"));

        assertDoesNotThrow(() -> signOutFlow.execute());
    }

    @Test
    void execute_doesNotThrowWhenLogoutFails() {
        VaadinSession session = mock(VaadinSession.class);
        when(session.getAttribute("authSession.token")).thenReturn("jwt-abc");
        VaadinSession.setCurrent(session);
        org.mockito.Mockito.doThrow(new IllegalStateException("downstream blew up"))
            .when(authenticationService).logout(any());

        assertDoesNotThrow(() -> signOutFlow.execute());
        // Still mints the new guest so the next register/login can proceed.
        verify(authenticationService).startGuestSession();
    }
}
