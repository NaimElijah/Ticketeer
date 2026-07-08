package com.ticketing.system.unit.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.ticketing.system.Core.Application.dto.RegisterRequestDTO;
import com.ticketing.system.Core.Application.services.AuthenticationService;
import com.ticketing.system.shared.exception.DuplicateEmailException;
import com.ticketing.system.shared.exception.DuplicateUsernameException;
import com.ticketing.system.shared.exception.GuestSessionRequiredException;
import com.ticketing.system.shared.exception.InvalidEmailFormatException;
import com.ticketing.system.shared.exception.WeakPasswordException;
import com.ticketing.system.Presentation.presenters.auth.RegisterPresenter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RegisterPresenterTest {

    private AuthenticationService authenticationService;
    private RegisterPresenter presenter;

    @BeforeEach
    void setUp() {
        authenticationService = mock(AuthenticationService.class);
        presenter = new RegisterPresenter(authenticationService);
    }

    @Test
    void success_returnsSuccessOutcome() {
        doNothing().when(authenticationService).register(any(RegisterRequestDTO.class));

        RegisterPresenter.Outcome outcome = presenter.attemptRegister(
            "adam", "adam@x.com", "password1", 22, "guest-sid"
        );

        assertInstanceOf(RegisterPresenter.Outcome.Success.class, outcome);
    }

    @Test
    void success_forwardsAllFieldsToTheService() {
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
    void duplicateUsername_returnsUsernameTaken() {
        doThrow(new DuplicateUsernameException("adam")).when(authenticationService).register(any());

        RegisterPresenter.Outcome outcome =
            presenter.attemptRegister("adam", "adam@x.com", "password1", 22, "guest-sid");

        assertInstanceOf(RegisterPresenter.Outcome.UsernameTaken.class, outcome);
    }

    @Test
    void duplicateEmail_returnsEmailTaken() {
        doThrow(new DuplicateEmailException("adam@x.com")).when(authenticationService).register(any());

        RegisterPresenter.Outcome outcome =
            presenter.attemptRegister("adam", "adam@x.com", "password1", 22, "guest-sid");

        assertInstanceOf(RegisterPresenter.Outcome.EmailTaken.class, outcome);
    }

    @Test
    void weakPassword_returnsWeakPasswordWithReason() {
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
    void invalidEmail_returnsInvalidEmail() {
        doThrow(new InvalidEmailFormatException("not-an-email"))
            .when(authenticationService).register(any());

        RegisterPresenter.Outcome outcome =
            presenter.attemptRegister("adam", "not-an-email", "password1", 22, "guest-sid");

        assertInstanceOf(RegisterPresenter.Outcome.InvalidEmail.class, outcome);
    }

    @Test
    void guestSessionRequired_returnsGuestSessionMissingWithMessage() {
        doThrow(new GuestSessionRequiredException("session is not a guest session"))
            .when(authenticationService).register(any());

        RegisterPresenter.Outcome outcome =
            presenter.attemptRegister("adam", "adam@x.com", "password1", 22, null);

        RegisterPresenter.Outcome.GuestSessionMissing miss =
            assertInstanceOf(RegisterPresenter.Outcome.GuestSessionMissing.class, outcome);
        assertEquals("session is not a guest session", miss.reason());
    }

    @Test
    void illegalArgument_returnsInvalidInputWithMessage() {
        doThrow(new IllegalArgumentException("Age cannot be negative"))
            .when(authenticationService).register(any());

        RegisterPresenter.Outcome outcome =
            presenter.attemptRegister("adam", "adam@x.com", "password1", -1, "guest-sid");

        RegisterPresenter.Outcome.InvalidInput bad =
            assertInstanceOf(RegisterPresenter.Outcome.InvalidInput.class, outcome);
        assertEquals("Age cannot be negative", bad.reason());
    }

    @Test
    void unexpectedRuntime_returnsFailureWithMessage() {
        doThrow(new IllegalStateException("database down"))
            .when(authenticationService).register(any());

        RegisterPresenter.Outcome outcome =
            presenter.attemptRegister("adam", "adam@x.com", "password1", 22, "guest-sid");

        RegisterPresenter.Outcome.Failure fail =
            assertInstanceOf(RegisterPresenter.Outcome.Failure.class, outcome);
        assertEquals("database down", fail.reason());
    }
}
