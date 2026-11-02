package com.ticketing.system.unit.presentation;

import static com.ticketing.system.unit.presentation.ViewHarness.button;
import static com.ticketing.system.unit.presentation.ViewHarness.click;
import static com.ticketing.system.unit.presentation.ViewHarness.field;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.identity.application.service.AuthenticationService;
import com.ticketing.system.notifications.application.port.in.INotificationService;
import com.ticketing.system.shared.dto.GuestSessionDTO;
import com.ticketing.system.ui.presenters.auth.LoginPresenter;
import com.ticketing.system.ui.presenters.auth.RegisterPresenter;
import com.ticketing.system.ui.presenters.auth.UsernameAvailabilityPresenter;
import com.ticketing.system.ui.session.FreshGuestSession;
import com.ticketing.system.ui.session.GuestSession;
import com.ticketing.system.ui.session.SessionIdentity;
import com.ticketing.system.ui.session.UnreadAlertsSendBack;
import com.ticketing.system.ui.views.auth.LoginView;
import com.ticketing.system.ui.views.auth.RegisterView;
import com.vaadin.flow.component.textfield.EmailField;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.PasswordField;
import com.vaadin.flow.component.textfield.TextField;

/**
 * Signing in and registering pass on a live guest session id: they read it through
 * {@link SessionIdentity}, which replaces a guest session that expired, instead of reading
 * {@link GuestSession} directly (#187, UI-A11).
 */
class SignInGuestSessionTest {

    // ---- UI-A11: login and register read the guest session id directly (#187) ----

    private static final String DEAD_GUEST_SESSION_ID = "expired-guest-sid";
    private static final String FRESH_GUEST_SESSION_ID = "fresh-guest-sid";

    private ViewHarness harness;
    private SessionIdentity sessionIdentity;

    @BeforeEach
    void setUp() {
        harness = new ViewHarness();
        GuestSession.setSessionId(DEAD_GUEST_SESSION_ID);
        AuthenticationService authenticationService = mock(AuthenticationService.class);
        when(authenticationService.isLiveGuestSession(DEAD_GUEST_SESSION_ID)).thenReturn(false);
        when(authenticationService.isLiveGuestSession(FRESH_GUEST_SESSION_ID)).thenReturn(true);
        when(authenticationService.startGuestSession())
            .thenReturn(new GuestSessionDTO(FRESH_GUEST_SESSION_ID, Instant.EPOCH));
        sessionIdentity = new SessionIdentity(authenticationService, new FreshGuestSession(authenticationService,
            new UnreadAlertsSendBack(mock(INotificationService.class))));
    }

    @AfterEach
    void tearDown() {
        harness.close();
    }

    @Test
    void GivenTheGuestSessionExpired_WhenTheVisitorSignsIn_ThenAFreshGuestSessionIdIsPassedOn() {
        LoginPresenter presenter = mock(LoginPresenter.class);
        when(presenter.attemptLogin(any(), any(), any())).thenReturn(new LoginPresenter.Outcome.InvalidCredentials());
        LoginView view = new LoginView(presenter, sessionIdentity);
        field(view, TextField.class, "Username").setValue("alice");
        field(view, PasswordField.class, "Password").setValue("secret-pw");

        click(button(view, "Sign in"));

        verify(presenter).attemptLogin("alice", "secret-pw", FRESH_GUEST_SESSION_ID);
    }

    @Test
    void GivenTheGuestSessionExpired_WhenTheVisitorRegisters_ThenAFreshGuestSessionIdIsPassedOn() {
        RegisterPresenter presenter = mock(RegisterPresenter.class);
        when(presenter.attemptRegister(any(), any(), any(), anyInt(), any()))
            .thenReturn(new RegisterPresenter.Outcome.UsernameTaken());
        UsernameAvailabilityPresenter availability = mock(UsernameAvailabilityPresenter.class);
        when(availability.check(any())).thenReturn(new UsernameAvailabilityPresenter.Outcome.Available());
        RegisterView view = new RegisterView(presenter, availability, sessionIdentity);
        field(view, TextField.class, "Username").setValue("alice");
        field(view, EmailField.class, "Email").setValue("alice@example.com");
        field(view, IntegerField.class, "Age").setValue(30);
        field(view, PasswordField.class, "Password").setValue("Secret-pw-123");
        field(view, PasswordField.class, "Confirm password").setValue("Secret-pw-123");

        click(button(view, "Create account"));

        verify(presenter).attemptRegister(eq("alice"), eq("alice@example.com"), eq("Secret-pw-123"), eq(30),
            eq(FRESH_GUEST_SESSION_ID));
    }
}
