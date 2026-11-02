package com.ticketing.system.ui.security;

import com.ticketing.system.shared.dto.LogoutRequestDTO;
import com.ticketing.system.identity.application.service.AuthenticationService;
import com.ticketing.system.ui.session.AuthSession;
import com.ticketing.system.ui.session.FreshGuestSession;
import com.ticketing.system.ui.session.GuestSession;
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
 *       so the server-side Session row is gone (UC-14), then</li>
 *   <li>handing over to {@link FreshGuestSession#restore()}, which clears
 *       the member's Vaadin-side state and pins a newly minted Guest
 *       session, mirroring what {@code GuestSessionBootstrap} does at
 *       session start.</li>
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
    private final FreshGuestSession freshGuestSession;

    public SignOutFlow(AuthenticationService authenticationService, FreshGuestSession freshGuestSession) {
        this.authenticationService = authenticationService;
        this.freshGuestSession = freshGuestSession;
    }

    /**
     * Revokes the visitor's token, if any, then pins a fresh guest session. A failed revocation is
     * logged at ERROR with its exception and does not stop the local sign-out: the visitor's Vaadin
     * state is cleared either way, but the server-side session, and so the token, may stay live.
     */
    public void execute() {
        String token = AuthSession.token();
        if (token != null && !token.isBlank()) {
            try {
                authenticationService.logout(new LogoutRequestDTO(token));
            } catch (RuntimeException e) {
                log.error("logout failed during sign-out; the session may still be live", e);
            }
        }

        freshGuestSession.restore();
    }
}
