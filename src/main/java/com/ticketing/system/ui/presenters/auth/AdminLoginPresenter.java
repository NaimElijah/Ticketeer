package com.ticketing.system.ui.presenters.auth;

import com.ticketing.system.shared.dto.AuthTokenDTO;
import com.ticketing.system.identity.application.service.AuthenticationService;
import com.ticketing.system.shared.exception.AccountLockedException;
import com.ticketing.system.shared.exception.AuthenticationFailedException;
import com.ticketing.system.ui.session.AuthSession;
import com.ticketing.system.ui.session.NotificationSession;
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
     * since admin and member ids share one id space (ADR-012).
     *
     * @param adminAuth the token {@link Outcome.Success} carried
     */
    public void signIn(AuthTokenDTO adminAuth) {
        unreadAlertsSendBack.sendBackForSignedInMember();
        NotificationSession.clear();
        AuthSession.storeAuth(adminAuth);
    }

    public sealed interface Outcome {
        record Success(AuthTokenDTO authToken) implements Outcome { }
        record InvalidCredentials() implements Outcome { }
        record Locked(String reason) implements Outcome { }
        record ServiceUnavailable() implements Outcome { }
        record Failure(String reason) implements Outcome { }
    }
}
