package com.ticketing.system.ui.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;

import com.ticketing.system.identity.application.service.AuthenticationService;
import com.ticketing.system.shared.dto.GuestSessionDTO;
import com.ticketing.system.testutil.LogCapture;
import com.ticketing.system.ui.session.GuestSession;
import com.ticketing.system.ui.session.MapBackedVaadinSession;
import com.vaadin.flow.server.ServiceInitEvent;
import com.vaadin.flow.server.SessionInitEvent;
import com.vaadin.flow.server.SessionInitListener;
import com.vaadin.flow.server.VaadinService;

import ch.qos.logback.classic.Level;

/**
 * {@link GuestSessionBootstrap} driven as Vaadin drives it: the test registers it through
 * {@code serviceInit} and starts a Vaadin session.
 */
class GuestSessionBootstrapTest {

    private AuthenticationService authenticationService;
    private SessionInitListener sessionStart;
    private LogCapture log;

    @BeforeEach
    void setUp() throws Exception {
        MapBackedVaadinSession.install();
        authenticationService = mock(AuthenticationService.class);
        VaadinService vaadinService = mock(VaadinService.class);
        ServiceInitEvent initEvent = mock(ServiceInitEvent.class);
        when(initEvent.getSource()).thenReturn(vaadinService);
        new GuestSessionBootstrap(authenticationService).serviceInit(initEvent);
        ArgumentCaptor<SessionInitListener> listener = ArgumentCaptor.forClass(SessionInitListener.class);
        verify(vaadinService).addSessionInitListener(listener.capture());
        sessionStart = listener.getValue();
        log = LogCapture.of(GuestSessionBootstrap.class);
    }

    @AfterEach
    void tearDown() {
        log.close();
        MapBackedVaadinSession.uninstall();
    }

    // ---- a visitor gets a guest session at once, and only one ----

    @Test
    void GivenNoGuestSession_WhenAVaadinSessionStarts_ThenTheStartedGuestSessionIsPinned() throws Exception {
        when(authenticationService.startGuestSession()).thenReturn(new GuestSessionDTO("guest-1", Instant.EPOCH));

        sessionStart.sessionInit(mock(SessionInitEvent.class));

        assertEquals("guest-1", GuestSession.sessionId(), "the guest session started must be the one pinned");
    }

    @Test
    void GivenAGuestSessionAlreadyPinned_WhenAVaadinSessionStarts_ThenNoNewOneIsStartedAndItStays()
            throws Exception {
        GuestSession.setSessionId("guest-0");

        sessionStart.sessionInit(mock(SessionInitEvent.class));

        verify(authenticationService, never()).startGuestSession();
        assertEquals("guest-0", GuestSession.sessionId());
    }

    // ---- UI-B6: a failed guest-session start is logged with its exception (#187) ----

    @Test
    void GivenTheGuestSessionCannotBeStarted_WhenAVaadinSessionStarts_ThenTheWarningCarriesTheException()
            throws Exception {
        when(authenticationService.startGuestSession())
                .thenThrow(new DataAccessResourceFailureException("connection refused"));

        sessionStart.sessionInit(mock(SessionInitEvent.class));

        assertNull(GuestSession.sessionId(), "a failed start must pin no guest session");
        assertEquals(List.of(DataAccessResourceFailureException.class.getName()),
                log.throwableClassNamesAt(Level.WARN), "the warning must carry the exception, not only its message");
    }
}
