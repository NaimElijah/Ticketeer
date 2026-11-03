package com.ticketing.system.ui.views.auth;

import com.ticketing.system.shared.dto.LoginDTO;
import com.ticketing.system.ui.components.Toasts;
import com.ticketing.system.ui.components.kit.Lk;
import com.ticketing.system.ui.components.kit.LkAuthCard;
import com.ticketing.system.ui.components.kit.LkFormErrors;
import com.ticketing.system.ui.layouts.MainLayout;
import com.ticketing.system.ui.presenters.auth.LoginPresenter;
import com.ticketing.system.ui.session.AuthSession;
import com.ticketing.system.ui.session.CartBadgeCache;
import com.ticketing.system.ui.session.NotificationSession;
import com.ticketing.system.ui.session.OrderSession;
import com.ticketing.system.ui.session.SessionIdentity;
import com.ticketing.system.ui.support.ServiceErrors;
import com.ticketing.system.ui.views.catalog.BrowseEventsView;
import com.vaadin.flow.component.Key;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.textfield.Autocomplete;
import com.vaadin.flow.component.textfield.PasswordField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.auth.AnonymousAllowed;

@Route(value = "login", layout = MainLayout.class)
@PageTitle("Sign In · TicketHub")
@AnonymousAllowed
public class LoginView extends LkAuthCard {

    private final LoginPresenter presenter;
    // The guest id comes from here, not GuestSession: a guest session expires a fixed time after it
    // starts, and SessionIdentity replaces a dead one before login is asked to promote it.
    private final SessionIdentity sessionIdentity;
    private final LkFormErrors errors = new LkFormErrors();

    public LoginView(LoginPresenter presenter, SessionIdentity sessionIdentity) {
        super("Welcome back",
              "Sign in to buy tickets, manage events, and track your orders.");
        this.presenter = presenter;
        this.sessionIdentity = sessionIdentity;

        // The autocomplete tokens let a browser or password manager fill the fields (#188, WCAG 1.3.5).
        TextField username = new TextField("Username");
        username.setRequired(true);
        username.setAutofocus(true);
        username.setAutocomplete(Autocomplete.USERNAME);
        username.setWidthFull();

        PasswordField password = new PasswordField("Password");
        password.setRequired(true);
        password.setAutocomplete(Autocomplete.CURRENT_PASSWORD);
        password.setWidthFull();

        Button signIn = new Button("Sign in", e -> attemptSignIn(username, password));
        signIn.addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_LARGE);
        signIn.setWidthFull();
        // Enter in a field signs in; on the page it would also take over Enter on the links below (#188).
        signIn.addClickShortcut(Key.ENTER).listenOn(username, password);

        // No password reset until #104 builds one: https://github.com/NaimElijah/Ticketeer/issues/104
        col(14, username, password, signIn);

        foot("Don't have an account?", "Create one", RegisterView.class);
        // This form signs in members only; an administrator signs in at AdminLoginView.
        foot("Platform administrator?", "Admin sign in", AdminLoginView.class);
    }

    private void attemptSignIn(TextField username, PasswordField password) {
        errors.clear();
        if (username.isEmpty()) {
            errors.reject(username, "Enter your username.");
        }
        if (password.isEmpty()) {
            errors.reject(password, "Enter your password.");
        }
        if (!errors.isEmpty()) {
            Toasts.failure("Please enter both username and password.");
            errors.focusFirst();
            return;
        }

        LoginPresenter.Outcome outcome = presenter.attemptLogin(
            username.getValue(),
            password.getValue(),
            sessionIdentity.guestSessionId()
        );

        switch (outcome) {
            case LoginPresenter.Outcome.Success ok -> onSuccess(ok.loginDTO());
            case LoginPresenter.Outcome.InvalidCredentials ignored ->
                Toasts.failure("Invalid username or password.");
            case LoginPresenter.Outcome.Locked locked ->
                Toasts.failure(Lk.withReason("Your account is temporarily locked", locked.reason()));
            case LoginPresenter.Outcome.GuestSessionMissing miss -> {
                Toasts.warn("Your session timed out — reloading…");
                UI.getCurrent().getPage().reload();
            }
            case LoginPresenter.Outcome.ServiceUnavailable ignored ->
                Toasts.failure(ServiceErrors.DB_UNAVAILABLE_MESSAGE);
            case LoginPresenter.Outcome.Failure fail ->
                Toasts.failure(Lk.withReason("Sign-in failed", fail.reason()));
        }
    }

    private void onSuccess(LoginDTO dto) {
        AuthSession.storeAuth(dto.authToken());
        // Login swaps the cart: the member's held order is restored and merged with the guest's.
        CartBadgeCache.invalidate();
        // A sign-in starts another visit; the kept order confirmation belonged to the last one.
        OrderSession.clear();
        NotificationSession.store(dto.notifications());
        // Member sign-in only: an administrator signs in at AdminLoginView.
        Toasts.success("Signed in as " + dto.authToken().username());
        UI.getCurrent().navigate(BrowseEventsView.class);
    }
}
