package com.ticketing.system.ui.session;

import com.ticketing.system.shared.dto.AuthTokenDTO;
import com.vaadin.flow.server.VaadinSession;

/**
 * Session-scoped holder for member auth state. Stores the JWT returned
 * by {@code AuthenticationService.login()} alongside the userId, display
 * name, and admin flag, so views can both pass the token to services
 * and render the persona without re-querying.
 *
 * <p>{@link #storeAuth(AuthTokenDTO)} is the only way in: it stores what a
 * successful {@code AuthenticationService.login()} or {@code signInAsAdmin()}
 * returned, so every signed-in session holds the token the backend issued.
 * Admin status comes from that token, never from the username. Which
 * usernames a member may not register is the identity service's answer
 * ({@code AuthenticationService.isUsernameReserved}), not this class's.
 */
public final class AuthSession {

    private static final String TOKEN_KEY     = "authSession.token";
    private static final String USER_ID_KEY   = "authSession.userId";
    private static final String EXPIRES_KEY   = "authSession.expiresAtEpochMillis";
    private static final String NAME_KEY      = "authSession.displayName";
    private static final String SIGNED_IN_KEY = "authSession.signedIn";
    private static final String ADMIN_KEY     = "authSession.admin";

    private AuthSession() { }

    // -- Real auth path ---------------------------------------------------

    /**
     * Store the result of a successful {@code AuthenticationService.login()}
     * or {@code signInAsAdmin()}.
     * Sets every field at once so reads are consistent.
     */
    public static void storeAuth(AuthTokenDTO dto) {
        VaadinSession s = VaadinSession.getCurrent();
        if (s == null || dto == null) return;
        s.setAttribute(TOKEN_KEY, dto.token());
        s.setAttribute(USER_ID_KEY, dto.userId());
        s.setAttribute(EXPIRES_KEY, dto.expiresAtEpochMillis());
        s.setAttribute(NAME_KEY, dto.username());
        s.setAttribute(SIGNED_IN_KEY, Boolean.TRUE);
        // Admin-ness comes from the token the backend issued (admin sign-in sets it), not from a
        // hardcoded username set — the dedicated /admin/sign-in flow is the only way to become admin.
        s.setAttribute(ADMIN_KEY, dto.isAdmin());
    }

    // -- Shared -----------------------------------------------------------

    /** Clears every auth attribute. Call this from sign-out. */
    public static void signOut() {
        VaadinSession s = VaadinSession.getCurrent();
        if (s == null) return;
        s.setAttribute(TOKEN_KEY, null);
        s.setAttribute(USER_ID_KEY, null);
        s.setAttribute(EXPIRES_KEY, null);
        s.setAttribute(NAME_KEY, null);
        s.setAttribute(SIGNED_IN_KEY, Boolean.FALSE);
        s.setAttribute(ADMIN_KEY, Boolean.FALSE);
    }

    // -- Reads ------------------------------------------------------------

    public static boolean isSignedIn() {
        VaadinSession s = VaadinSession.getCurrent();
        if (s == null) return false;
        return Boolean.TRUE.equals(s.getAttribute(SIGNED_IN_KEY));
    }

    public static boolean isAdmin() {
        VaadinSession s = VaadinSession.getCurrent();
        if (s == null) return false;
        return Boolean.TRUE.equals(s.getAttribute(ADMIN_KEY));
    }

    public static String displayName() {
        VaadinSession s = VaadinSession.getCurrent();
        if (s == null) return null;
        return (String) s.getAttribute(NAME_KEY);
    }

    /** The JWT stored by {@link #storeAuth}; {@code null} when signed out. */
    public static String token() {
        VaadinSession s = VaadinSession.getCurrent();
        if (s == null) return null;
        return (String) s.getAttribute(TOKEN_KEY);
    }

    public static Integer userId() {
        VaadinSession s = VaadinSession.getCurrent();
        if (s == null) return null;
        return (Integer) s.getAttribute(USER_ID_KEY);
    }

    public static long expiresAtEpochMillis() {
        VaadinSession s = VaadinSession.getCurrent();
        if (s == null) return 0L;
        Object v = s.getAttribute(EXPIRES_KEY);
        return v instanceof Long l ? l : 0L;
    }
}
