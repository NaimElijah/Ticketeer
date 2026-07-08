package com.ticketing.system.ui.session;
import com.ticketing.system.identity.application.service.AuthenticationService;
import com.ticketing.system.identity.domain.Session;

import com.vaadin.flow.server.VaadinSession;

/**
 * Session-scoped holder for the visitor's guest sessionId.
 *
 * <p>Every Vaadin session that enters the app gets a Guest session minted
 * by {@link com.ticketing.system.ui.security.GuestSessionBootstrap}
 * before any view is rendered. The sessionId returned by
 * {@code AuthenticationService.startGuestSession()} is stashed here so
 * downstream views can pass it as the {@code guestSessionId} field on
 * {@code LoginRequestDTO} / {@code RegisterRequestDTO} (both require it
 * per D10a).
 *
 * <p>After a successful login the same sessionId is promoted in place to
 * a Member session — the value stays valid, so no clearing is needed at
 * login time. {@link #clear()} is only called from sign-out.
 */
public final class GuestSession {

    private static final String SESSION_ID_KEY = "guestSession.sessionId";

    private GuestSession() { }

    /** Current guest sessionId, or {@code null} when no Vaadin session / not yet bootstrapped. */
    public static String sessionId() {
        VaadinSession s = VaadinSession.getCurrent();
        if (s == null) return null;
        return (String) s.getAttribute(SESSION_ID_KEY);
    }

    public static void setSessionId(String sessionId) {
        VaadinSession s = VaadinSession.getCurrent();
        if (s != null) s.setAttribute(SESSION_ID_KEY, sessionId);
    }

    public static void clear() {
        VaadinSession s = VaadinSession.getCurrent();
        if (s != null) s.setAttribute(SESSION_ID_KEY, null);
    }
}
