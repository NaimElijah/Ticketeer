package com.ticketing.system.ui.presenters.auth;

import com.ticketing.system.Core.Application.dto.LoginDTO;
import com.ticketing.system.Core.Application.dto.LoginRequestDTO;
import com.ticketing.system.identity.application.service.AuthenticationService;
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

    @Autowired
    public LoginPresenter(AuthenticationService authenticationService) {
        this.authenticationService = authenticationService;
    }

    /**
     * Calls {@code AuthenticationService.login()} with the supplied
     * credentials and the current guest sessionId. Translates checked
     * domain exceptions into a typed outcome the view can render
     * without try/catch noise.
     */
    public Outcome attemptLogin(String username, String rawPassword, String guestSessionId) {
        try {
            LoginDTO result = authenticationService.login(
                new LoginRequestDTO(username, rawPassword, guestSessionId)
            );
            return new Outcome.Success(result);
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
            return new Outcome.Failure(e.getMessage());
        }
    }

    /** Sealed outcome the view switches on to drive Toasts + navigation. */
    public sealed interface Outcome {
        record Success(LoginDTO loginDTO) implements Outcome { }
        record InvalidCredentials() implements Outcome { }
        record GuestSessionMissing(String reason) implements Outcome { }
        record ServiceUnavailable() implements Outcome { }
        record Failure(String reason) implements Outcome { }
    }
}
