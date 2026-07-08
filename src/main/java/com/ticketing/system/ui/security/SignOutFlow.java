package com.ticketing.system.ui.security;
import com.ticketing.system.identity.domain.Session;

import com.ticketing.system.shared.dto.GuestSessionDTO;
import com.ticketing.system.shared.dto.LogoutRequestDTO;
import com.ticketing.system.identity.application.service.AuthenticationService;
import com.ticketing.system.ui.session.AuthSession;
import com.ticketing.system.ui.session.CurrentCompanies;
import com.ticketing.system.ui.session.GuestSession;
import com.ticketing.system.ui.session.NotificationSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Canonical sign-out path for the Vaadin frontend.
 *
 * <p>The bare {@link AuthSession#signOut()} only clears the in-memory
 * Vaadin attributes; the underlying {@code Session} row stays valid
 * until expiry and — more importantly — the {@link GuestSession#sessionId()}
 * still points at the now-promoted member session. The next register or
 * login from the same Vaadin session then fails with
 * {@code GuestSessionRequiredException}.
 *
 * <p>This flow restores the invariant that "every Vaadin session has a
 * fresh Guest sessionId" by:
 * <ol>
 *   <li>invalidating the current JWT through {@code AuthenticationService}
 *       so the server-side Session row is gone (UC-14),</li>
 *   <li>clearing the Vaadin-side {@link AuthSession} attributes, and</li>
 *   <li>minting a new Guest session and pinning it on
 *       {@link GuestSession}, mirroring what
 *       {@code GuestSessionBootstrap} does at session start.</li>
 * </ol>
 *
 * <p>All four sign-out call sites (the three layouts and the dev panel)
 * route through {@link #execute()} so the post-logout invariant is
 * upheld uniformly.
 */
@Component
@Slf4j
public class SignOutFlow {

    private final AuthenticationService authenticationService;

    public SignOutFlow(AuthenticationService authenticationService) {
        this.authenticationService = authenticationService;
    }

    public void execute() {
        String token = AuthSession.token();
        if (token != null && !token.isBlank()) {
            try {
                authenticationService.logout(new LogoutRequestDTO(token));
            } catch (RuntimeException e) {
                log.warn("logout call failed during sign-out flow: {}", e.getMessage());
            }
        }

        AuthSession.signOut();
        CurrentCompanies.clearCurrentCompany();
        NotificationSession.clear();

        try {
            GuestSessionDTO fresh = authenticationService.startGuestSession();
            GuestSession.setSessionId(fresh.sessionId());
        } catch (RuntimeException e) {
            log.warn("failed to refresh guest session after sign-out: {}", e.getMessage());
            GuestSession.clear();
        }
    }
}
