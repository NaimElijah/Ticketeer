package com.ticketing.system.Presentation.views.auth;
import com.ticketing.system.identity.domain.Admin;

import com.ticketing.system.Presentation.components.Toasts;
import com.ticketing.system.Presentation.components.kit.LkAuthCard;
import com.ticketing.system.Presentation.layouts.MainLayout;
import com.ticketing.system.Presentation.presenters.auth.AdminLoginPresenter;
import com.ticketing.system.Presentation.session.AuthSession;
import com.ticketing.system.Presentation.support.ServiceErrors;
import com.ticketing.system.Presentation.views.admin.AdminDashboardView;
import com.vaadin.flow.component.Key;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.textfield.PasswordField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.auth.AnonymousAllowed;

/**
 * Dedicated platform-admin sign-in (#290) at {@code /admin/sign-in}. Authenticates against the
 * persisted admin pool through {@link AdminLoginPresenter} (real JWT, lockout, audit) — never the
 * member pool. Members reach their own form at {@code /login}.
 */
@Route(value = "admin/sign-in", layout = MainLayout.class)
@PageTitle("Admin Sign In · TicketHub")
@AnonymousAllowed
public class AdminLoginView extends LkAuthCard {

    private final AdminLoginPresenter presenter;

    public AdminLoginView(AdminLoginPresenter presenter) {
        super("Admin sign-in", "Restricted — platform administrators only.");
        this.presenter = presenter;

        TextField username = new TextField("Admin username");
        username.setRequired(true);
        username.setAutofocus(true);
        username.setWidthFull();

        PasswordField password = new PasswordField("Password");
        password.setRequired(true);
        password.setWidthFull();

        Button signIn = new Button("Sign in", e -> attempt(username, password));
        signIn.addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_LARGE);
        signIn.setWidthFull();
        signIn.addClickShortcut(Key.ENTER);

        col(14, username, password, signIn);
        foot("Not an administrator?", "Member sign in →", LoginView.class);
    }

    private void attempt(TextField username, PasswordField password) {
        if (username.isEmpty() || password.isEmpty()) {
            Toasts.failure("Please enter both username and password.");
            return;
        }

        AdminLoginPresenter.Outcome outcome =
            presenter.attemptAdminLogin(username.getValue(), password.getValue());

        switch (outcome) {
            case AdminLoginPresenter.Outcome.Success ok -> {
                AuthSession.storeAuth(ok.authToken());
                Toasts.success("Signed in as admin · " + ok.authToken().username());
                UI.getCurrent().navigate(AdminDashboardView.class);
            }
            case AdminLoginPresenter.Outcome.InvalidCredentials ignored ->
                Toasts.failure("Invalid admin username or password.");
            case AdminLoginPresenter.Outcome.Locked locked ->
                Toasts.failure("Your account is temporarily locked after too many failed attempts. Please try again later.");
            case AdminLoginPresenter.Outcome.ServiceUnavailable ignored ->
                Toasts.failure(ServiceErrors.DB_UNAVAILABLE_MESSAGE);
            case AdminLoginPresenter.Outcome.Failure fail ->
                Toasts.failure("Sign-in failed — please try again.");
        }
    }
}
