package com.ticketing.system.ui.session;

import org.springframework.stereotype.Component;

import com.ticketing.system.identity.application.service.AuthenticationService;
import com.ticketing.system.shared.dto.GuestSessionDTO;

import lombok.extern.slf4j.Slf4j;

/**
 * Restores the invariant that every Vaadin session has a fresh guest session id once the member is
 * gone.
 *
 * <p>Login promotes the guest session in place ({@link GuestSession}), so after the member leaves, the
 * id {@link GuestSession} holds is the member's own session: dead for browsing and still mapped to the
 * member's cart on the guest paths. {@link #restore()} drops the member's Vaadin-side state and pins a
 * newly minted guest session in its place.
 *
 * <p>Called by {@code SignOutFlow} after an explicit logout, and by {@link SessionIdentity} when the
 * stored member token is no longer live (expired or revoked). {@code SignOutFlow}'s Javadoc describes
 * the register/login failure this prevents.
 */
@Component
@Slf4j
public class FreshGuestSession {

    private final AuthenticationService authenticationService;

    public FreshGuestSession(AuthenticationService authenticationService) {
        this.authenticationService = authenticationService;
    }

    /**
     * Clears the member's auth, current company and notification inbox, then pins a new guest session.
     *
     * <p>Never throws: if no guest session can be started, the stale id is cleared instead, so the dead
     * member session is never left pinned and the view that triggered this still renders.
     */
    public void restore() {
        AuthSession.signOut();
        CurrentCompanies.clearCurrentCompany();
        NotificationSession.clear();

        try {
            GuestSessionDTO fresh = authenticationService.startGuestSession();
            GuestSession.setSessionId(fresh.sessionId());
        } catch (RuntimeException e) {
            log.warn("failed to start a fresh guest session: {}", e.getMessage());
            GuestSession.clear();
        }
    }
}
