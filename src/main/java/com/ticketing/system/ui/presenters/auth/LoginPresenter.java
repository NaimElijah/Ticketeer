package com.ticketing.system.ui.presenters.auth;

import com.ticketing.system.shared.dto.LoginDTO;
import com.ticketing.system.shared.dto.LoginRequestDTO;
import com.ticketing.system.identity.application.service.AuthenticationService;
import com.ticketing.system.shared.exception.AccountLockedException;
import com.ticketing.system.shared.exception.AuthenticationFailedException;
import com.ticketing.system.shared.exception.GuestSessionRequiredException;
import com.ticketing.system.ui.support.ServiceErrors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * MVP presenter for {@code LoginView}. Holds no Vaadin imports so the
 * outcome → UI translation lives in the view and the service-call
 * decision tree is unit-testable in isolation.
 *
 * <p>Returns a typed {@link Outcome} so the view's job is a switch
 * over the sealed hierarchy — no exception handling in click handlers.
 */
@Component
public class LoginPresenter {

    private final AuthenticationService authenticationService;

    /** Creates the presenter over the authentication service. */
    @Autowired
    public LoginPresenter(AuthenticationService authenticationService) {
        this.authenticationService = authenticationService;
    }

    /**
     * Calls {@code AuthenticationService.login()} with the supplied
     * credentials and the current guest sessionId. Translates checked
     * domain exceptions into a typed outcome the view can render
     * without try/catch noise.
     *
     * @param username       the username as typed
     * @param rawPassword    the password as typed
     * @param guestSessionId the visitor's current guest session id; the service refuses a login
     *     without a live one
     * @return the {@link Outcome} for the service's answer; see its variants
     */
    public Outcome attemptLogin(String username, String rawPassword, String guestSessionId) {
        try {
            LoginDTO result = authenticationService.login(
                new LoginRequestDTO(username, rawPassword, guestSessionId)
            );
            return new Outcome.Success(result);
        } catch (AccountLockedException e) {
            return new Outcome.Locked(e.getMessage());
        } catch (AuthenticationFailedException e) {
            return new Outcome.InvalidCredentials();
        } catch (GuestSessionRequiredException e) {
            return new Outcome.GuestSessionMissing(e.getMessage());
        } catch (RuntimeException e) {
            // A DB outage surfaces here as a connectivity exception — tell it apart from a real
            // failure so the user sees "service unavailable", not a misleading "sign-in failed".
            if (ServiceErrors.isDatabaseUnavailable(e)) {
                return new Outcome.ServiceUnavailable();
            }
            return new Outcome.Failure(ServiceErrors.userMessage(e));
        }
    }

    /** Sealed outcome the view switches on to drive Toasts + navigation. */
    public sealed interface Outcome {
        /**
         * The member is signed in.
         *
         * @param loginDTO the member's token, restored active order and delivered notifications
         */
        record Success(LoginDTO loginDTO) implements Outcome { }

        /** The service refused the credentials ({@code AuthenticationFailedException}). */
        record InvalidCredentials() implements Outcome { }

        /**
         * Too many failed attempts on this username; the password was not checked.
         *
         * @param reason the lockout's own text, which says when to try again
         */
        record Locked(String reason) implements Outcome { }

        /**
         * The guest session id is missing, unknown, expired or not a guest's.
         *
         * @param reason the exception's message
         */
        record GuestSessionMissing(String reason) implements Outcome { }

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
