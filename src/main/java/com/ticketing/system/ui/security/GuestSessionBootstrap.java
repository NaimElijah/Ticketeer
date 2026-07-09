package com.ticketing.system.ui.security;

import com.ticketing.system.shared.dto.GuestSessionDTO;
import com.ticketing.system.identity.application.service.AuthenticationService;
import com.ticketing.system.ui.session.GuestSession;
import com.vaadin.flow.server.ServiceInitEvent;
import com.vaadin.flow.server.VaadinServiceInitListener;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Mints a Guest session at the start of every Vaadin session and stashes
 * the returned {@code sessionId} on {@link GuestSession} so downstream
 * views can attach it to {@code LoginRequestDTO} / {@code RegisterRequestDTO}
 * (both require a non-null {@code guestSessionId} per spec D10a).
 *
 * <p>Runs in addition to {@link AuthBootstrap}: this one mints the guest
 * sessionId once per VaadinSession; that one runs the per-navigation
 * auth and capability gates. Order between the two does not matter —
 * the navigation guard fires later, on a {@code BeforeEnterEvent}, by
 * which point the session listener has already populated the sessionId.
 */
@Component
@Slf4j
public class GuestSessionBootstrap implements VaadinServiceInitListener {

    private final AuthenticationService authenticationService;

    public GuestSessionBootstrap(AuthenticationService authenticationService) {
        this.authenticationService = authenticationService;
    }

    @Override
    public void serviceInit(ServiceInitEvent event) {
        event.getSource().addSessionInitListener(sessionInit -> {
            if (GuestSession.sessionId() != null) return;
            try {
                GuestSessionDTO dto = authenticationService.startGuestSession();
                GuestSession.setSessionId(dto.sessionId());
                log.debug("guest session bootstrapped sid={}", dto.sessionId());
            } catch (Exception e) {
                log.warn("failed to start guest session at bootstrap: {}", e.getMessage());
            }
        });
    }
}
