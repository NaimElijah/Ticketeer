package com.ticketing.system.unit.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.ticketing.system.identity.application.dto.RegisterRequestDTO;
import com.ticketing.system.identity.application.service.AuthenticationService;
import com.ticketing.system.shared.exception.DuplicateEmailException;
import com.ticketing.system.shared.exception.DuplicateUsernameException;
import com.ticketing.system.shared.exception.GuestSessionRequiredException;
import com.ticketing.system.shared.exception.InvalidEmailFormatException;
import com.ticketing.system.shared.exception.WeakPasswordException;
import com.ticketing.system.ui.presenters.auth.RegisterPresenter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import jakarta.persistence.PersistenceException;
import com.ticketing.system.ui.support.ServiceErrors;
import org.springframework.dao.DataAccessResourceFailureException;

class RegisterPresenterTest {

    // What AuthenticationService.register throws for an age outside the range it accepts.
    private static final String AGE_OUT_OF_RANGE =
        "Age must be from " + AuthenticationService.MIN_AGE + " to " + AuthenticationService.MAX_AGE;

    private AuthenticationService authenticationService;
    private RegisterPresenter presenter;

    @BeforeEach
    void setUp() {
        authenticationService = mock(AuthenticationService.class);
        presenter = new RegisterPresenter(authenticationService);
    }

    @Test
    void GivenValidRegistrationDetails_WhenAttemptRegister_ThenReturnsSuccessOutcome() {
        doNothing().when(authenticationService).register(any(RegisterRequestDTO.class));

        RegisterPresenter.Outcome outcome = presenter.attemptRegister(
            "adam", "adam@x.com", "password1", 22, "guest-sid"
        );

        assertInstanceOf(RegisterPresenter.Outcome.Success.class, outcome);
    }

    @Test
    void GivenValidRegistrationDetails_WhenAttemptRegister_ThenForwardsAllFieldsToTheService() {
        doNothing().when(authenticationService).register(any(RegisterRequestDTO.class));

        presenter.attemptRegister("adam", "adam@x.com", "password1", 22, "guest-sid");

        ArgumentCaptor<RegisterRequestDTO> captor = ArgumentCaptor.forClass(RegisterRequestDTO.class);
        verify(authenticationService).register(captor.capture());
        RegisterRequestDTO sent = captor.getValue();
        assertEquals("adam", sent.username());
        assertEquals("adam@x.com", sent.email());
        assertEquals("password1", sent.rawPassword());
        assertEquals("guest-sid", sent.guestSessionId());
        assertEquals(22, sent.age());
    }

    @Test
    void GivenADuplicateUsername_WhenAttemptRegister_ThenReturnsUsernameTaken() {
        doThrow(new DuplicateUsernameException("adam")).when(authenticationService).register(any());

        RegisterPresenter.Outcome outcome =
            presenter.attemptRegister("adam", "adam@x.com", "password1", 22, "guest-sid");

        assertInstanceOf(RegisterPresenter.Outcome.UsernameTaken.class, outcome);
    }

    @Test
    void GivenADuplicateEmail_WhenAttemptRegister_ThenReturnsEmailTaken() {
        doThrow(new DuplicateEmailException()).when(authenticationService).register(any());

        RegisterPresenter.Outcome outcome =
            presenter.attemptRegister("adam", "adam@x.com", "password1", 22, "guest-sid");

        assertInstanceOf(RegisterPresenter.Outcome.EmailTaken.class, outcome);
    }

    @Test
    void GivenAWeakPassword_WhenAttemptRegister_ThenReturnsWeakPasswordWithReason() {
        doThrow(new WeakPasswordException("must be at least 8 characters"))
            .when(authenticationService).register(any());

        RegisterPresenter.Outcome outcome =
            presenter.attemptRegister("adam", "adam@x.com", "short", 22, "guest-sid");

        RegisterPresenter.Outcome.WeakPassword weak =
            assertInstanceOf(RegisterPresenter.Outcome.WeakPassword.class, outcome);
        assertTrue(weak.reason().contains("must be at least 8 characters"),
            "reason should preserve the WeakPasswordException's strength detail");
    }

    @Test
    void GivenAnInvalidEmail_WhenAttemptRegister_ThenReturnsInvalidEmail() {
        doThrow(new InvalidEmailFormatException())
            .when(authenticationService).register(any());

        RegisterPresenter.Outcome outcome =
            presenter.attemptRegister("adam", "not-an-email", "password1", 22, "guest-sid");

        assertInstanceOf(RegisterPresenter.Outcome.InvalidEmail.class, outcome);
    }

    @Test
    void GivenAGuestSessionIsRequired_WhenAttemptRegister_ThenReturnsGuestSessionMissingWithMessage() {
        doThrow(new GuestSessionRequiredException("session is not a guest session"))
            .when(authenticationService).register(any());

        RegisterPresenter.Outcome outcome =
            presenter.attemptRegister("adam", "adam@x.com", "password1", 22, null);

        RegisterPresenter.Outcome.GuestSessionMissing miss =
            assertInstanceOf(RegisterPresenter.Outcome.GuestSessionMissing.class, outcome);
        assertEquals("session is not a guest session", miss.reason());
    }

    @Test
    void GivenAnIllegalArgument_WhenAttemptRegister_ThenReturnsInvalidInputWithMessage() {
        doThrow(new IllegalArgumentException(AGE_OUT_OF_RANGE))
            .when(authenticationService).register(any());

        RegisterPresenter.Outcome outcome =
            presenter.attemptRegister("adam", "adam@x.com", "password1", -1, "guest-sid");

        RegisterPresenter.Outcome.InvalidInput bad =
            assertInstanceOf(RegisterPresenter.Outcome.InvalidInput.class, outcome);
        assertEquals(AGE_OUT_OF_RANGE, bad.reason());
    }

    @Test
    void GivenAnIllegalStateException_WhenAttemptRegister_ThenFailureCarriesItsMessage() {
        doThrow(new IllegalStateException("Registration is paused for maintenance"))
            .when(authenticationService).register(any());

        RegisterPresenter.Outcome outcome =
            presenter.attemptRegister("adam", "adam@x.com", "password1", 22, "guest-sid");

        RegisterPresenter.Outcome.Failure fail =
            assertInstanceOf(RegisterPresenter.Outcome.Failure.class, outcome);
        assertEquals("Registration is paused for maintenance", fail.reason());
    }

    // ---- an internal failure reads as generic text, never as its own (#189) ----

    @Test
    void GivenAnInternalFailure_WhenAttemptRegister_ThenFailureCarriesTheGenericText() {
        doThrow(new PersistenceException("could not execute statement [insert into users]"))
            .when(authenticationService).register(any());

        assertEquals(new RegisterPresenter.Outcome.Failure(ServiceErrors.GENERIC_FAILURE_MESSAGE),
            presenter.attemptRegister("adam", "adam@x.com", "password1", 22, "guest-sid"));
    }

    @Test
    void GivenTheDatabaseIsDown_WhenAttemptRegister_ThenFailureSaysTheServiceIsUnavailable() {
        doThrow(new DataAccessResourceFailureException("could not get a connection"))
            .when(authenticationService).register(any());

        assertEquals(new RegisterPresenter.Outcome.Failure(ServiceErrors.DB_UNAVAILABLE_MESSAGE),
            presenter.attemptRegister("adam", "adam@x.com", "password1", 22, "guest-sid"));
    }
}
