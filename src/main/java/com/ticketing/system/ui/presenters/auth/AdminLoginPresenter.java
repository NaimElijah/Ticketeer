package com.ticketing.system.ui.presenters.auth;

import com.ticketing.system.shared.dto.AuthTokenDTO;
import com.ticketing.system.identity.application.service.AuthenticationService;
import com.ticketing.system.shared.exception.AccountLockedException;
import com.ticketing.system.shared.exception.AuthenticationFailedException;
import com.ticketing.system.ui.session.AuthSession;
import com.ticketing.system.ui.session.NotificationSession;
import com.ticketing.system.ui.session.OrderSession;
import com.ticketing.system.ui.session.UnreadAlertsSendBack;
import com.ticketing.system.ui.support.ServiceErrors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * MVP presenter for {@code AdminLoginView} (#290). Holds no Vaadin imports; the
 * view switches over the typed {@link Outcome}. Mirrors {@link LoginPresenter}.
 */
@Component
public class AdminLoginPresenter {

    private final AuthenticationService authenticationService;
    private final UnreadAlertsSendBack unreadAlertsSendBack;

    /**
     * Creates the presenter.
     *
     * @param authenticationService the service that signs the admin in
     * @param unreadAlertsSendBack  returns a previously signed-in member's shown-but-unread alerts to
     *     pending when the browser session becomes an admin's
     */
    @Autowired
    public AdminLoginPresenter(AuthenticationService authenticationService,
            UnreadAlertsSendBack unreadAlertsSendBack) {
        this.authenticationService = authenticationService;
        this.unreadAlertsSendBack = unreadAlertsSendBack;
    }

    /** Authenticates against the admin pool; translates domain exceptions into a typed outcome. */
    public Outcome attemptAdminLogin(String username, String rawPassword) {
        try {
            AuthTokenDTO token = authenticationService.signInAsAdmin(username, rawPassword);
            return new Outcome.Success(token);
        } catch (AccountLockedException e) {
            return new Outcome.Locked(e.getMessage());
        } catch (AuthenticationFailedException e) {
            return new Outcome.InvalidCredentials();
        } catch (RuntimeException e) {
            // A DB outage surfaces here as a connectivity exception — tell it apart from a real
            // failure so the user sees "service unavailable", not a misleading "sign-in failed".
            if (ServiceErrors.isDatabaseUnavailable(e)) {
                return new Outcome.ServiceUnavailable();
            }
            return new Outcome.Failure(ServiceErrors.userMessage(e));
        }
    }

    /**
     * Makes this browser session the admin's, after {@link #attemptAdminLogin} succeeded. A member who
     * signed in on this session before gets their shown-but-unread alerts sent back to pending, while
     * the session still names them; then the bell is emptied, and only then is the admin's auth stored,
     * since admin and member ids share one id space (ADR-012). The kept order confirmation
     * ({@code OrderSession}) is dropped too: a sign-in starts another visit.
     *
     * @param adminAuth the token {@link Outcome.Success} carried
     */
    public void signIn(AuthTokenDTO adminAuth) {
        unreadAlertsSendBack.sendBackForSignedInMember();
        NotificationSession.clear();
        AuthSession.storeAuth(adminAuth);
        // A sign-in starts another visit; the kept order confirmation belonged to the last one.
        OrderSession.clear();
    }

    /** Sealed outcome of an admin sign-in attempt, which the view switches on. */
    public sealed interface Outcome {
        /**
         * The service signed the admin in. Nothing is stored yet; {@link #signIn} does that.
         *
         * @param authToken the admin's token
         */
        record Success(AuthTokenDTO authToken) implements Outcome { }

        /** The service refused the credentials ({@code AuthenticationFailedException}). */
        record InvalidCredentials() implements Outcome { }

        /**
         * Too many failed attempts on this username; the password was not checked.
         *
         * @param reason the lockout's own text, which says when to try again
         */
        record Locked(String reason) implements Outcome { }

        /** The database is unreachable, so the attempt could not be decided either way. */
        record ServiceUnavailable() implements Outcome { }

        /**
         * Any other failure.
         *
         * @param reason the text the user may read, from {@code ServiceErrors.userMessage}
         */
        record Failure(String reason) implements Outcome { }
    }
}
