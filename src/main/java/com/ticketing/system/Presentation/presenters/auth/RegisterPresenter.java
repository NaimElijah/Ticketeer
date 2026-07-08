package com.ticketing.system.Presentation.presenters.auth;

import com.ticketing.system.Core.Application.dto.RegisterRequestDTO;
import com.ticketing.system.identity.application.service.AuthenticationService;
import com.ticketing.system.shared.exception.DuplicateEmailException;
import com.ticketing.system.shared.exception.DuplicateUsernameException;
import com.ticketing.system.shared.exception.GuestSessionRequiredException;
import com.ticketing.system.shared.exception.InvalidEmailFormatException;
import com.ticketing.system.shared.exception.WeakPasswordException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * MVP presenter for {@code RegisterView}. Holds no Vaadin imports so
 * the outcome → UI translation lives in the view and the service-call
 * decision tree is unit-testable in isolation.
 *
 * <p>{@code AuthenticationService.register()} returns void — the
 * session stays Guest after register completes; the user still needs
 * to log in to become a Member. {@link Outcome.Success} therefore
 * carries no payload.
 */
@Component
public class RegisterPresenter {

    private final AuthenticationService authenticationService;

    @Autowired
    public RegisterPresenter(AuthenticationService authenticationService) {
        this.authenticationService = authenticationService;
    }

    public Outcome attemptRegister(String username, String email, String rawPassword,
                                   int age, String guestSessionId) {
        try {
            authenticationService.register(
                new RegisterRequestDTO(username, email, rawPassword, guestSessionId, age)
            );
            return new Outcome.Success();
        } catch (DuplicateUsernameException e) {
            return new Outcome.UsernameTaken();
        } catch (DuplicateEmailException e) {
            return new Outcome.EmailTaken();
        } catch (WeakPasswordException e) {
            return new Outcome.WeakPassword(e.getMessage());
        } catch (InvalidEmailFormatException e) {
            return new Outcome.InvalidEmail();
        } catch (GuestSessionRequiredException e) {
            return new Outcome.GuestSessionMissing(e.getMessage());
        } catch (IllegalArgumentException e) {
            return new Outcome.InvalidInput(e.getMessage());
        } catch (RuntimeException e) {
            return new Outcome.Failure(e.getMessage());
        }
    }

    public sealed interface Outcome {
        record Success() implements Outcome { }
        record UsernameTaken() implements Outcome { }
        record EmailTaken() implements Outcome { }
        record WeakPassword(String reason) implements Outcome { }
        record InvalidEmail() implements Outcome { }
        record GuestSessionMissing(String reason) implements Outcome { }
        record InvalidInput(String reason) implements Outcome { }
        record Failure(String reason) implements Outcome { }
    }
}
