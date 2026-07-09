package com.ticketing.system.ui.session;
import com.ticketing.system.identity.application.service.AuthenticationService;
import com.ticketing.system.identity.domain.Admin;
import com.ticketing.system.identity.domain.Session;

import com.ticketing.system.shared.dto.AuthTokenDTO;
import com.vaadin.flow.server.VaadinSession;

import java.util.Set;

/**
 * Session-scoped holder for member auth state. Stores the JWT returned
 * by {@code AuthenticationService.login()} alongside the userId, display
 * name, and admin flag, so views can both pass the token to services
 * and render the persona without re-querying.
 *
 * <p>Two write paths exist:
 * <ul>
 *   <li>{@link #storeAuth(AuthTokenDTO)} — the real login path. Called
 *       by {@code LoginPresenter} after a successful
 *       {@code AuthenticationService.login()}.</li>
 *   <li>{@link #signIn(String)} / {@link #signInAsAdmin(String)} /
 *       {@link #setAdmin(boolean)} — dev-only flag toggles used by the
 *       {@code DevPanel} to switch personas without going through the
 *       real auth slice. These leave {@link #token()} {@code null}; do
 *       not call them from production code paths.</li>
 * </ul>
 *
 * <p>Admin status is derived from the username against {@link #ADMIN_USERNAMES}
 * (the closed pool of platform-admin handles). The pool stays here so
 * the unified {@code LoginView} and {@code RegisterView} keep a single
 * source of truth for which usernames belong to the admin path.
 */
public final class AuthSession {

    private static final String TOKEN_KEY     = "authSession.token";
    private static final String USER_ID_KEY   = "authSession.userId";
    private static final String EXPIRES_KEY   = "authSession.expiresAtEpochMillis";
    private static final String NAME_KEY      = "authSession.displayName";
    private static final String SIGNED_IN_KEY = "authSession.signedIn";
    private static final String ADMIN_KEY     = "authSession.admin";

    /**
     * Closed set of usernames that belong to the platform-admin pool.
     * The unified {@code LoginView} routes users with one of these
     * names into the admin path; {@code RegisterView} refuses them so
     * nobody can register a member account that shadows an admin name.
     */
    public static final Set<String> ADMIN_USERNAMES = Set.of(
        "admin", "platform.admin", "bar.miyara"
    );

    private AuthSession() { }

    public static boolean isAdminUsername(String name) {
        return name != null && ADMIN_USERNAMES.contains(name.trim().toLowerCase());
    }

    // -- Real auth path ---------------------------------------------------

    /**
     * Store the result of a successful {@code AuthenticationService.login()}.
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

    // -- Dev-only flag toggles -------------------------------------------

    /** Dev path — sign in without a token. Leaves admin flag untouched. */
    public static void signIn(String displayName) {
        VaadinSession s = VaadinSession.getCurrent();
        if (s == null) return;
        s.setAttribute(SIGNED_IN_KEY, Boolean.TRUE);
        s.setAttribute(NAME_KEY, displayName);
    }

    /** Dev path — sign in without a token, set admin flag. */
    public static void signInAsAdmin(String displayName) {
        VaadinSession s = VaadinSession.getCurrent();
        if (s == null) return;
        s.setAttribute(SIGNED_IN_KEY, Boolean.TRUE);
        s.setAttribute(NAME_KEY, displayName);
        s.setAttribute(ADMIN_KEY, Boolean.TRUE);
    }

    /**
     * Dev path — toggle the admin flag independently of member sign-in.
     * Setting {@code true} on a signed-out session also signs the user
     * in with a default name.
     */
    public static void setAdmin(boolean isAdmin) {
        VaadinSession s = VaadinSession.getCurrent();
        if (s == null) return;
        s.setAttribute(ADMIN_KEY, isAdmin);
        if (isAdmin) {
            s.setAttribute(SIGNED_IN_KEY, Boolean.TRUE);
            if (s.getAttribute(NAME_KEY) == null) {
                s.setAttribute(NAME_KEY, "admin");
            }
        }
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

    /** Raw JWT from the real login path. {@code null} when in a dev-toggle session. */
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
