package com.ticketing.system.ui.session;

import org.springframework.stereotype.Component;

import com.ticketing.system.identity.application.port.out.SessionManager;

/**
 * Single source of truth for "member or guest, and which backend credential to use."
 * A member is a visitor whose token still validates; everyone else — including a member
 * whose token has EXPIRED — is a guest using the registered {@link GuestSession} id.
 */
@Component
public class SessionIdentity {

    private final SessionManager sessionManager;

    public SessionIdentity(SessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    public boolean isMember() {
        String token = AuthSession.token();
        return token != null && sessionManager.validateToken(token);
    }

    public String memberToken() {
        return isMember() ? AuthSession.token() : null;
    }

    public String guestSessionId() {
        return GuestSession.sessionId();
    }

    public int memberUserId() {
        String token = AuthSession.token();
        return (token != null && sessionManager.validateToken(token))
            ? sessionManager.extractUserId(token) : 0;
    }

    public String credential() {
        String token = AuthSession.token();
        return (token != null && sessionManager.validateToken(token))
            ? token : GuestSession.sessionId();
    }
}