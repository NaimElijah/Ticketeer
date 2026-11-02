package com.ticketing.system.ui.views.auth;

import com.ticketing.system.shared.dto.LoginDTO;
import com.ticketing.system.ui.components.Toasts;
import com.ticketing.system.ui.components.kit.Lk;
import com.ticketing.system.ui.components.kit.LkAuthCard;
import com.ticketing.system.ui.layouts.MainLayout;
import com.ticketing.system.ui.presenters.auth.LoginPresenter;
import com.ticketing.system.ui.session.AuthSession;
import com.ticketing.system.ui.session.CartBadgeCache;
import com.ticketing.system.ui.session.NotificationSession;
import com.ticketing.system.ui.session.OrderSession;
import com.ticketing.system.ui.session.SessionIdentity;
import com.ticketing.system.ui.support.ServiceErrors;
import com.ticketing.system.ui.views.admin.AdminDashboardView;
import com.ticketing.system.ui.views.catalog.BrowseEventsView;
import com.vaadin.flow.component.Key;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
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

    public LoginView(LoginPresenter presenter, SessionIdentity sessionIdentity) {
        super("Welcome back",
              "Sign in to buy tickets, manage events, and track your orders.");
        this.presenter = presenter;
        this.sessionIdentity = sessionIdentity;

        TextField username = new TextField("Username");
        username.setRequired(true);
        username.setAutofocus(true);
        username.setWidthFull();

        PasswordField password = new PasswordField("Password");
        password.setRequired(true);
        password.setWidthFull();

        Button signIn = new Button("Sign in", e -> attemptSignIn(username, password));
        signIn.addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_LARGE);
        signIn.setWidthFull();
        signIn.addClickShortcut(Key.ENTER);

        // No remember-me, password reset (#104) or social sign-in: nothing behind them exists (#187, UI-A19).
        col(14, username, password, signIn);

        foot("Don't have an account?", "Create one →", RegisterView.class);
        // This form signs in members only; an administrator signs in at AdminLoginView.
        foot("Platform administrator?", "Admin sign in →", AdminLoginView.class);
    }

    private void attemptSignIn(TextField username, PasswordField password) {
        if (username.isEmpty() || password.isEmpty()) {
            Toasts.failure("Please enter both username and password.");
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
        String name = dto.authToken().username();
        if (AuthSession.isAdmin()) {
            Toasts.success("Signed in as admin · " + name);
            UI.getCurrent().navigate(AdminDashboardView.class);
        } else {
            Toasts.success("Signed in as " + name);
            UI.getCurrent().navigate(BrowseEventsView.class);
        }
    }
}
