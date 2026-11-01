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
 * <p>Called by {@code SignOutFlow} after an explicit logout, by {@link SessionIdentity} when the
 * stored member token is no longer live (expired or revoked), and by {@code AuthBootstrap} when a
 * stored admin token is no longer live. {@code SignOutFlow}'s Javadoc describes the register/login
 * failure this prevents.
 *
 * <p>{@link #renewGuest()} is the narrower step for a guest session id that expired on its own: it
 * replaces only that id and signs nobody out.
 */
@Component
@Slf4j
public class FreshGuestSession {

    private final AuthenticationService authenticationService;
    private final UnreadAlertsSendBack unreadAlertsSendBack;

    public FreshGuestSession(AuthenticationService authenticationService,
            UnreadAlertsSendBack unreadAlertsSendBack) {
        this.authenticationService = authenticationService;
        this.unreadAlertsSendBack = unreadAlertsSendBack;
    }

    /**
     * Sends the member's shown-but-unread alerts back to pending, so their next login shows them
     * ({@link UnreadAlertsSendBack}), then clears the member's auth, current company, notification inbox
     * and recent searches, and pins a new guest session.
     *
     * <p>Never throws: if no guest session can be started, the stale id is cleared instead, so the dead
     * member session is never left pinned and the view that triggered this still renders.
     */
    public void restore() {
        // First, while the auth still names the member: the session-end listener skips a signed-out
        // session.
        unreadAlertsSendBack.sendBackForSignedInMember();
        AuthSession.signOut();
        CurrentCompanies.clearCurrentCompany();
        NotificationSession.clear();
        RecentSearches.clear();
        pinNewGuestSession();
    }

    /**
     * Replaces only the guest session id with a newly minted one. The stored token, the admin flag,
     * the current company and the recent searches stay, so a signed-in admin on the storefront stays
     * signed in.
     *
     * <p>Never throws: if no guest session can be started, the dead id is cleared, as in
     * {@link #restore()}.
     */
    public void renewGuest() {
        pinNewGuestSession();
    }

    private void pinNewGuestSession() {
        try {
            GuestSessionDTO fresh = authenticationService.startGuestSession();
            GuestSession.setSessionId(fresh.sessionId());
        } catch (RuntimeException e) {
            log.warn("failed to start a fresh guest session: {}", e.getMessage());
            GuestSession.clear();
        }
    }
}
