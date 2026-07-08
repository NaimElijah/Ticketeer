package com.ticketing.system.Presentation.presenters.auth;

import com.ticketing.system.Core.Application.dto.AuthTokenDTO;
import com.ticketing.system.Core.Application.services.AuthenticationService;
import com.ticketing.system.shared.exception.AccountLockedException;
import com.ticketing.system.shared.exception.AuthenticationFailedException;
import com.ticketing.system.Presentation.support.ServiceErrors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * MVP presenter for {@code AdminLoginView} (#290). Holds no Vaadin imports; the
 * view switches over the typed {@link Outcome}. Mirrors {@link LoginPresenter}.
 */
@Component
public class AdminLoginPresenter {

    private final AuthenticationService authenticationService;

    @Autowired
    public AdminLoginPresenter(AuthenticationService authenticationService) {
        this.authenticationService = authenticationService;
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
            return new Outcome.Failure(e.getMessage());
        }
    }

    public sealed interface Outcome {
        record Success(AuthTokenDTO authToken) implements Outcome { }
        record InvalidCredentials() implements Outcome { }
        record Locked(String reason) implements Outcome { }
        record ServiceUnavailable() implements Outcome { }
        record Failure(String reason) implements Outcome { }
    }
}
