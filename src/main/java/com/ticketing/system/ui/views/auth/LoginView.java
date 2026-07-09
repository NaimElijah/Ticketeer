package com.ticketing.system.ui.views.auth;

import com.ticketing.system.shared.dto.LoginDTO;
import com.ticketing.system.ui.components.Toasts;
import com.ticketing.system.ui.components.kit.LkAuthCard;
import com.ticketing.system.ui.components.kit.LkCheckRow;
import com.ticketing.system.ui.layouts.MainLayout;
import com.ticketing.system.ui.presenters.auth.LoginPresenter;
import com.ticketing.system.ui.session.AuthSession;
import com.ticketing.system.ui.session.GuestSession;
import com.ticketing.system.ui.session.NotificationSession;
import com.ticketing.system.ui.support.ServiceErrors;
import com.ticketing.system.ui.views.admin.AdminDashboardView;
import com.ticketing.system.ui.views.catalog.BrowseEventsView;
import com.vaadin.flow.component.Key;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.NativeButton;
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

    public LoginView(LoginPresenter presenter) {
        super("Welcome back",
              "Sign in to buy tickets, manage events, and track your orders.");
        this.presenter = presenter;

        TextField username = new TextField("Username");
        username.setRequired(true);
        username.setAutofocus(true);
        username.setWidthFull();

        PasswordField password = new PasswordField("Password");
        password.setRequired(true);
        password.setWidthFull();

        LkCheckRow remember = new LkCheckRow("Remember me", false)
            .wrapperClass("auth-remember");

        Div rememberRow = new Div();
        rememberRow.getStyle()
            .set("display", "flex")
            .set("justify-content", "space-between")
            .set("align-items", "center");
        rememberRow.add(remember, forgotPasswordLink());

        Button signIn = new Button("Sign in", e -> attemptSignIn(username, password));
        signIn.addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_LARGE);
        signIn.setWidthFull();
        signIn.addClickShortcut(Key.ENTER);

        col(14, username, password, rememberRow, signIn);
        divider("or continue with");

        Div social = new Div();
        social.getStyle().set("display", "flex").set("gap", "10px");
        social.add(socialBtn("Google"), socialBtn("Apple"));
        add(social);

        foot("Don't have an account?", "Create one →", RegisterView.class);

        // TEMP (dev, #290): discreet path to the dedicated admin sign-in during development.
        // Drop this link for a real deployment — admins just bookmark /admin/sign-in.
        Anchor adminLink = new Anchor("admin/sign-in", "Admin sign-in →");
        adminLink.addClassName("bz-link");
        adminLink.getStyle().set("display", "block").set("margin-top", "10px")
            .set("text-align", "center").set("font-size", "12.5px");
        add(adminLink);
    }

    private Anchor forgotPasswordLink() {
        Anchor a = new Anchor("javascript:void(0)", "Forgot password?");
        a.addClassName("bz-link");
        return a;
    }

    private NativeButton socialBtn(String label) {
        NativeButton b = new NativeButton(label);
        b.addClassName("lk-btn");
        b.addClassName("lk-btn-secondary");
        b.addClassName("lk-btn-m");
        b.getStyle().set("flex", "1");
        return b;
    }

    private void attemptSignIn(TextField username, PasswordField password) {
        if (username.isEmpty() || password.isEmpty()) {
            Toasts.failure("Please enter both username and password.");
            return;
        }

        LoginPresenter.Outcome outcome = presenter.attemptLogin(
            username.getValue(),
            password.getValue(),
            GuestSession.sessionId()
        );

        switch (outcome) {
            case LoginPresenter.Outcome.Success ok -> onSuccess(ok.loginDTO());
            case LoginPresenter.Outcome.InvalidCredentials ignored ->
                Toasts.failure("Invalid username or password.");
            case LoginPresenter.Outcome.GuestSessionMissing miss -> {
                Toasts.warn("Your session timed out — reloading…");
                UI.getCurrent().getPage().reload();
            }
            case LoginPresenter.Outcome.ServiceUnavailable ignored ->
                Toasts.failure(ServiceErrors.DB_UNAVAILABLE_MESSAGE);
            case LoginPresenter.Outcome.Failure fail ->
                Toasts.failure("Sign-in failed — please try again.");
        }
    }

    private void onSuccess(LoginDTO dto) {
        AuthSession.storeAuth(dto.authToken());
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
