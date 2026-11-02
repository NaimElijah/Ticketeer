package com.ticketing.system.unit.presentation;

import static com.ticketing.system.unit.presentation.ViewHarness.button;
import static com.ticketing.system.unit.presentation.ViewHarness.click;
import static com.ticketing.system.unit.presentation.ViewHarness.field;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.ticketing.system.identity.application.service.AuthenticationService;
import com.ticketing.system.shared.dto.LoginRequestDTO;
import com.ticketing.system.shared.exception.AccountLockedException;
import com.ticketing.system.ui.presenters.auth.LoginPresenter;
import com.ticketing.system.ui.session.SessionIdentity;
import com.ticketing.system.ui.views.auth.LoginView;
import com.vaadin.flow.component.textfield.PasswordField;
import com.vaadin.flow.component.textfield.TextField;

/**
 * A member whose account is locked is told so, with the lockout's own text (#187, UI-A2): the member
 * sign-in gets a {@code Locked} outcome, as the admin sign-in has.
 */
class LoginPresenterLockedTest {

    // ---- UI-A2: a locked member account reads as a lockout (#187) ----

    private static final String LOCKOUT = "Too many failed attempts. Try again in about 7 minutes.";

    @Test
    void GivenTheMemberAccountIsLocked_WhenTheMemberSignsIn_ThenTheOutcomeIsLockedWithTheLockoutText() {
        AuthenticationService authenticationService = mock(AuthenticationService.class);
        when(authenticationService.login(any(LoginRequestDTO.class))).thenThrow(new AccountLockedException(LOCKOUT));

        LoginPresenter.Outcome outcome = new LoginPresenter(authenticationService)
            .attemptLogin("alice", "secret-pw", "guest-sid");

        LoginPresenter.Outcome.Locked locked = assertInstanceOf(LoginPresenter.Outcome.Locked.class, outcome);
        assertEquals(LOCKOUT, locked.reason());
    }

    @Test
    void GivenTheMemberAccountIsLocked_WhenTheMemberSignsIn_ThenTheToastShowsTheLockout() {
        LoginPresenter presenter = mock(LoginPresenter.class);
        when(presenter.attemptLogin(any(), any(), any())).thenReturn(new LoginPresenter.Outcome.Locked(LOCKOUT));
        try (ViewHarness harness = new ViewHarness()) {
            LoginView view = new LoginView(presenter, mock(SessionIdentity.class));
            field(view, TextField.class, "Username").setValue("alice");
            field(view, PasswordField.class, "Password").setValue("secret-pw");

            click(button(view, "Sign in"));

            List<String> toasts = harness.toasts();
            assertTrue(toasts.stream().anyMatch(toast -> toast.contains(LOCKOUT)),
                "expected the lockout text in a toast, got: " + toasts);
        }
    }
}
