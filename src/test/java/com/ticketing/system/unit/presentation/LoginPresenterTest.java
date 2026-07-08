package com.ticketing.system.unit.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ticketing.system.Core.Application.dto.AuthTokenDTO;
import com.ticketing.system.Core.Application.dto.LoginDTO;
import com.ticketing.system.Core.Application.dto.LoginRequestDTO;
import com.ticketing.system.identity.application.service.AuthenticationService;
import com.ticketing.system.shared.exception.AuthenticationFailedException;
import com.ticketing.system.shared.exception.GuestSessionRequiredException;
import com.ticketing.system.Presentation.presenters.auth.LoginPresenter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.CannotCreateTransactionException;

class LoginPresenterTest {

    private AuthenticationService authenticationService;
    private LoginPresenter presenter;

    @BeforeEach
    void setUp() {
        authenticationService = mock(AuthenticationService.class);
        presenter = new LoginPresenter(authenticationService);
    }

    @Test
    void success_returnsSuccessOutcomeWrappingTheLoginDTO() {
        LoginDTO expected = new LoginDTO(
            new AuthTokenDTO("jwt-token", 9_999_999_999L, 42, "adam"),
            null,
            java.util.List.of()
        );
        when(authenticationService.login(any(LoginRequestDTO.class))).thenReturn(expected);

        LoginPresenter.Outcome outcome = presenter.attemptLogin("adam", "password123", "guest-sid");

        LoginPresenter.Outcome.Success ok = assertInstanceOf(LoginPresenter.Outcome.Success.class, outcome);
        assertSame(expected, ok.loginDTO());
    }

    @Test
    void success_forwardsAllThreeArgumentsToTheService() {
        when(authenticationService.login(any(LoginRequestDTO.class))).thenReturn(
            new LoginDTO(new AuthTokenDTO("t", 0L, 1, "u"), null, java.util.List.of())
        );

        presenter.attemptLogin("adam", "password123", "guest-sid");

        ArgumentCaptor<LoginRequestDTO> captor = ArgumentCaptor.forClass(LoginRequestDTO.class);
        verify(authenticationService).login(captor.capture());
        LoginRequestDTO sent = captor.getValue();
        assertEquals("adam", sent.username());
        assertEquals("password123", sent.rawPassword());
        assertEquals("guest-sid", sent.guestSessionId());
    }

    @Test
    void authenticationFailed_returnsInvalidCredentials() {
        when(authenticationService.login(any())).thenThrow(new AuthenticationFailedException());

        LoginPresenter.Outcome outcome = presenter.attemptLogin("adam", "wrong", "guest-sid");

        assertInstanceOf(LoginPresenter.Outcome.InvalidCredentials.class, outcome);
    }

    @Test
    void guestSessionRequired_returnsGuestSessionMissingWithMessage() {
        when(authenticationService.login(any()))
            .thenThrow(new GuestSessionRequiredException("guest session expired"));

        LoginPresenter.Outcome outcome = presenter.attemptLogin("adam", "password123", "guest-sid");

        LoginPresenter.Outcome.GuestSessionMissing miss =
            assertInstanceOf(LoginPresenter.Outcome.GuestSessionMissing.class, outcome);
        assertEquals("guest session expired", miss.reason());
    }

    @Test
    void unexpectedRuntime_returnsFailureWithMessage() {
        when(authenticationService.login(any()))
            .thenThrow(new IllegalStateException("database down"));

        LoginPresenter.Outcome outcome = presenter.attemptLogin("adam", "password123", "guest-sid");

        LoginPresenter.Outcome.Failure fail =
            assertInstanceOf(LoginPresenter.Outcome.Failure.class, outcome);
        assertEquals("database down", fail.reason());
    }

    @Test
    void databaseUnavailable_returnsServiceUnavailable() {
        // A real DB outage surfaces as a connectivity exception (here, can't open a transaction)
        // — it must map to ServiceUnavailable, not the generic Failure shown for unexpected errors.
        when(authenticationService.login(any()))
            .thenThrow(new CannotCreateTransactionException("Could not open JPA EntityManager for transaction"));

        LoginPresenter.Outcome outcome = presenter.attemptLogin("adam", "password123", "guest-sid");

        assertInstanceOf(LoginPresenter.Outcome.ServiceUnavailable.class, outcome);
    }
}
