package com.ticketing.system.ui.session;

import org.springframework.stereotype.Component;

import com.ticketing.system.identity.application.port.out.SessionManager;
import com.ticketing.system.shared.exception.InvalidTokenException;
import com.ticketing.system.shared.exception.SessionExpiredException;

/**
 * Single source of truth for "member or guest, and which backend credential to use."
 * A member is a visitor whose token still validates; everyone else — including a member
 * whose token has EXPIRED or been revoked — is a guest using the registered {@link GuestSession} id.
 */
@Component
public class SessionIdentity {

    private final SessionManager sessionManager;

    public SessionIdentity(SessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    public boolean isMember() {
        return liveMemberToken() != null;
    }

    public String memberToken() {
        return liveMemberToken();
    }

    public String guestSessionId() {
        return GuestSession.sessionId();
    }

    public int memberUserId() {
        String token = liveMemberToken();
        return token != null ? sessionManager.extractUserId(token) : 0;
    }

    public String credential() {
        String token = liveMemberToken();
        return token != null ? token : GuestSession.sessionId();
    }

    /**
     * The signed-in member's token while it still validates, else {@code null}. Validation throws for
     * an expired or revoked token rather than returning false; that outcome is what makes the visitor a
     * guest, so it is answered here instead of reaching the caller.
     */
    private String liveMemberToken() {
        String token = AuthSession.token();
        if (token == null) {
            return null;
        }
        try {
            return sessionManager.validateToken(token) ? token : null;
        } catch (SessionExpiredException | InvalidTokenException e) {
            return null;
        }
    }
}
