package com.ticketing.system.unit.presentation;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ticketing.system.Core.Application.dto.AuthTokenDTO;
import com.ticketing.system.Core.Application.services.AuthenticationService;
import com.ticketing.system.shared.exception.AccountLockedException;
import com.ticketing.system.shared.exception.AuthenticationFailedException;
import com.ticketing.system.Presentation.presenters.auth.AdminLoginPresenter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class AdminLoginPresenterTest {

    private AuthenticationService authenticationService;
    private AdminLoginPresenter presenter;

    @BeforeEach
    void setUp() {
        authenticationService = mock(AuthenticationService.class);
        presenter = new AdminLoginPresenter(authenticationService);
    }

    @Test
    void success_returnsSuccessWrappingTheToken() {
        AuthTokenDTO token = new AuthTokenDTO("jwt", 9_999_999_999L, 1, "root");
        when(authenticationService.signInAsAdmin("root", "pw")).thenReturn(token);

        AdminLoginPresenter.Outcome outcome = presenter.attemptAdminLogin("root", "pw");

        AdminLoginPresenter.Outcome.Success ok =
            assertInstanceOf(AdminLoginPresenter.Outcome.Success.class, outcome);
        assertSame(token, ok.authToken());
    }

    @Test
    void authenticationFailed_returnsInvalidCredentials() {
        when(authenticationService.signInAsAdmin("root", "wrong"))
            .thenThrow(new AuthenticationFailedException());

        assertInstanceOf(AdminLoginPresenter.Outcome.InvalidCredentials.class,
            presenter.attemptAdminLogin("root", "wrong"));
    }

    @Test
    void accountLocked_returnsLocked() {
        when(authenticationService.signInAsAdmin("root", "pw"))
            .thenThrow(new AccountLockedException("locked for 15 minutes"));

        assertInstanceOf(AdminLoginPresenter.Outcome.Locked.class,
            presenter.attemptAdminLogin("root", "pw"));
    }

    @Test
    void databaseUnavailable_returnsServiceUnavailable() {
        // A DB outage surfaces as a connectivity exception — must map to ServiceUnavailable.
        when(authenticationService.signInAsAdmin("root", "pw"))
            .thenThrow(new DataAccessResourceFailureException("could not get a connection"));

        assertInstanceOf(AdminLoginPresenter.Outcome.ServiceUnavailable.class,
            presenter.attemptAdminLogin("root", "pw"));
    }

    @Test
    void unexpectedRuntime_returnsFailure() {
        when(authenticationService.signInAsAdmin("root", "pw"))
            .thenThrow(new IllegalStateException("boom"));

        assertInstanceOf(AdminLoginPresenter.Outcome.Failure.class,
            presenter.attemptAdminLogin("root", "pw"));
    }
}
