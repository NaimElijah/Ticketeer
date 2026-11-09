package com.ticketing.system.ui.presenters.auth;

import com.ticketing.system.identity.application.dto.RegisterRequestDTO;
import com.ticketing.system.identity.application.service.AuthenticationService;
import com.ticketing.system.shared.exception.DuplicateEmailException;
import com.ticketing.system.shared.exception.DuplicateUsernameException;
import com.ticketing.system.shared.exception.GuestSessionRequiredException;
import com.ticketing.system.shared.exception.InvalidEmailFormatException;
import com.ticketing.system.shared.exception.WeakPasswordException;
import com.ticketing.system.ui.support.ServiceErrors;
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

    /** The longest email registration accepts, so the form can cap its field at the same length. */
    public static final int MAX_EMAIL_LENGTH = AuthenticationService.MAX_EMAIL_LENGTH;

    /** The youngest age registration accepts, so the form asks for the same range. */
    public static final int MIN_AGE = AuthenticationService.MIN_AGE;

    /** The oldest age registration accepts, so the form asks for the same range. */
    public static final int MAX_AGE = AuthenticationService.MAX_AGE;

    private final AuthenticationService authenticationService;

    /** Creates the presenter over the authentication service. */
    @Autowired
    public RegisterPresenter(AuthenticationService authenticationService) {
        this.authenticationService = authenticationService;
    }

    /**
     * Registers a member and translates the service's refusals into a typed outcome.
     *
     * @param username       the username as typed
     * @param email          the email address as typed
     * @param rawPassword    the password as typed
     * @param age            the age as entered; the accepted range is {@link #MIN_AGE} to {@link #MAX_AGE}
     * @param guestSessionId the visitor's current guest session id; registration needs a live one
     * @return {@link Outcome.Success} when the member is registered, otherwise the variant for the
     *     refusal; see {@link Outcome}
     */
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
            return new Outcome.Failure(ServiceErrors.userMessage(e));
        }
    }

    /** Sealed outcome of a registration attempt, which the view switches on. */
    public sealed interface Outcome {
        /** The member is registered; the visitor still has to log in. */
        record Success() implements Outcome { }

        /** The username is already taken, or is one no member may register. */
        record UsernameTaken() implements Outcome { }

        /** The email address is already registered. */
        record EmailTaken() implements Outcome { }

        /**
         * The service refused the password.
         *
         * @param reason the exception's message, which reaches the user
         */
        record WeakPassword(String reason) implements Outcome { }

        /** The service refused the email address's format or length. */
        record InvalidEmail() implements Outcome { }

        /**
         * The guest session id is missing, unknown, expired or not a guest's.
         *
         * @param reason the exception's message
         */
        record GuestSessionMissing(String reason) implements Outcome { }

        /**
         * The service refused a blank username, the username's characters or the age's range with an
         * {@code IllegalArgumentException}.
         *
         * @param reason the exception's message, which reaches the user
         */
        record InvalidInput(String reason) implements Outcome { }

        /**
         * Any other failure.
         *
         * @param reason the text the user may read, from {@code ServiceErrors.userMessage}
         */
        record Failure(String reason) implements Outcome { }
    }
}
