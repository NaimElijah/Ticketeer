package com.ticketing.system.ui.views.auth;

import com.ticketing.system.ui.components.Toasts;
import com.ticketing.system.ui.components.kit.Lk;
import com.ticketing.system.ui.components.kit.LkAuthCard;
import com.ticketing.system.ui.components.kit.LkFieldRules;
import com.ticketing.system.ui.components.kit.LkFormErrors;
import com.ticketing.system.ui.layouts.MainLayout;
import com.ticketing.system.ui.presenters.auth.AdminLoginPresenter;
import com.ticketing.system.ui.support.ServiceErrors;
import com.ticketing.system.ui.views.admin.AdminDashboardView;
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

/**
 * Dedicated platform-admin sign-in (upstream#290) at {@code /admin/sign-in}. Authenticates against the
 * persisted admin pool through {@link AdminLoginPresenter} (real JWT, lockout, audit) — never the
 * member pool. Members reach their own form at {@code /login}.
 */
@Route(value = "admin/sign-in", layout = MainLayout.class)
@PageTitle("Admin Sign In · TicketHub")
@AnonymousAllowed
public class AdminLoginView extends LkAuthCard {

    private static final String ENTER_USERNAME = "Enter your admin username.";
    private static final String ENTER_PASSWORD = "Enter your password.";

    private final AdminLoginPresenter presenter;
    private final LkFormErrors errors = new LkFormErrors();

    /**
     * Builds the admin sign-in form.
     */
    public AdminLoginView(AdminLoginPresenter presenter) {
        super("Admin sign-in", "Restricted — platform administrators only.");
        this.presenter = presenter;

        // The autocomplete tokens let a browser or password manager fill the fields (#188, WCAG 1.3.5).
        TextField username = new TextField("Admin username");
        username.setRequired(true);
        LkFieldRules.sayWhenEmpty(username, ENTER_USERNAME);
        username.setAutofocus(true);
        username.setAutocomplete(Autocomplete.USERNAME);
        username.setWidthFull();

        PasswordField password = new PasswordField("Password");
        password.setRequired(true);
        LkFieldRules.sayWhenEmpty(password, ENTER_PASSWORD);
        password.setAutocomplete(Autocomplete.CURRENT_PASSWORD);
        password.setWidthFull();

        Button signIn = new Button("Sign in", e -> attempt(username, password));
        signIn.addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_LARGE);
        signIn.setWidthFull();
        // Enter in a field signs in; on the page it would also take over Enter on the link below (#188).
        signIn.addClickShortcut(Key.ENTER).listenOn(username, password);

        col(14, username, password, signIn);
        foot("Not an administrator?", "Member sign in", LoginView.class);
    }

    private void attempt(TextField username, PasswordField password) {
        errors.clear();
        if (username.isEmpty()) {
            errors.reject(username, ENTER_USERNAME);
        }
        if (password.isEmpty()) {
            errors.reject(password, ENTER_PASSWORD);
        }
        if (!errors.isEmpty()) {
            Toasts.failure("Please enter both username and password.");
            errors.focusFirst();
            return;
        }

        AdminLoginPresenter.Outcome outcome =
            presenter.attemptAdminLogin(username.getValue(), password.getValue());

        switch (outcome) {
            case AdminLoginPresenter.Outcome.Success ok -> {
                presenter.signIn(ok.authToken());
                Toasts.success("Signed in as admin · " + ok.authToken().username());
                UI.getCurrent().navigate(AdminDashboardView.class);
            }
            case AdminLoginPresenter.Outcome.InvalidCredentials ignored ->
                Toasts.failure("Invalid admin username or password.");
            case AdminLoginPresenter.Outcome.Locked locked ->
                Toasts.failure(Lk.withReason("Your account is temporarily locked", locked.reason()));
            case AdminLoginPresenter.Outcome.ServiceUnavailable ignored ->
                Toasts.failure(ServiceErrors.DB_UNAVAILABLE_MESSAGE);
            case AdminLoginPresenter.Outcome.Failure fail ->
                Toasts.failure(Lk.withReason("Sign-in failed", fail.reason()));
        }
    }
}
