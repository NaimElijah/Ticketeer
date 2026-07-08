package com.ticketing.system.ui.views.auth;

import com.ticketing.system.ui.components.Toasts;
import com.ticketing.system.ui.components.auth.PasswordStrengthMeter;
import com.ticketing.system.ui.components.kit.LkAuthCard;
import com.ticketing.system.ui.components.kit.LkCheckRow;
import com.ticketing.system.ui.layouts.MainLayout;
import com.ticketing.system.ui.presenters.auth.RegisterPresenter;
import com.ticketing.system.ui.presenters.auth.UsernameAvailabilityPresenter;
import com.ticketing.system.ui.session.AuthSession;
import com.ticketing.system.ui.session.GuestSession;
import com.vaadin.flow.component.Key;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.textfield.EmailField;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.PasswordField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.value.ValueChangeMode;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.auth.AnonymousAllowed;

@Route(value = "register", layout = MainLayout.class)
@PageTitle("Register · TicketHub")
@AnonymousAllowed
public class RegisterView extends LkAuthCard {

    private static final String USERNAME_HELP_DEFAULT = "This is how you'll appear to organizers.";

    private final RegisterPresenter presenter;
    private final UsernameAvailabilityPresenter usernameAvailability;

    public RegisterView(RegisterPresenter presenter,
                        UsernameAvailabilityPresenter usernameAvailability) {
        super("Create your account",
              "Join TicketHub to discover events and book in seconds.");
        this.presenter = presenter;
        this.usernameAvailability = usernameAvailability;

        TextField username = new TextField("Username");
        username.setPlaceholder("3–32 characters, letters and digits");
        username.setHelperText(USERNAME_HELP_DEFAULT);
        username.setRequired(true);
        username.setWidthFull();
        username.setAutofocus(true);
        // Debounced live availability check — 300 ms after the last keystroke
        // the LAZY mode fires a value-change event; the presenter returns a
        // typed Outcome that drives the suffix icon, helper text, and the
        // invalid styling. See [V2-AUTH-FORM-VAL] in wire-up-plan.md.
        username.setValueChangeMode(ValueChangeMode.LAZY);
        username.setValueChangeTimeout(300);
        username.addValueChangeListener(e -> applyUsernameFeedback(username, e.getValue()));

        EmailField email = new EmailField("Email");
        email.setPlaceholder("you@email.com");
        email.setRequired(true);
        email.setWidthFull();

        IntegerField age = new IntegerField("Age");
        age.setMin(1);
        age.setMax(120);
        age.setStepButtonsVisible(true);
        age.setRequiredIndicatorVisible(true);
        age.setWidthFull();

        PasswordField password = new PasswordField("Password");
        password.setPlaceholder("At least 8 characters");
        password.setRequired(true);
        password.setWidthFull();
        // Live strength feedback. EAGER mode fires on every keystroke so the
        // bar and rule chips track the user's input directly.
        PasswordStrengthMeter strengthMeter = new PasswordStrengthMeter();
        password.setValueChangeMode(ValueChangeMode.EAGER);
        password.addValueChangeListener(e -> strengthMeter.update(e.getValue()));

        PasswordField confirm = new PasswordField("Confirm password");
        confirm.setPlaceholder("Re-enter your password");
        confirm.setRequired(true);
        confirm.setWidthFull();

        LkCheckRow terms = new LkCheckRow("I agree to the Terms and Privacy Policy", false)
            .wrapperClass("auth-remember");

        Button create = new Button("Create account",
            e -> attemptRegister(username, email, age, password, confirm));
        create.addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_LARGE);
        create.setWidthFull();
        create.addClickShortcut(Key.ENTER);

        col(14, username, email, age, password, strengthMeter, confirm, terms, create);
        foot("Already have an account?", "Sign in →", LoginView.class);
    }

    private void applyUsernameFeedback(TextField field, String value) {
        UsernameAvailabilityPresenter.Outcome outcome = usernameAvailability.check(value);
        switch (outcome) {
            case UsernameAvailabilityPresenter.Outcome.Empty ignored -> {
                field.setSuffixComponent(null);
                field.setHelperText(USERNAME_HELP_DEFAULT);
                field.setInvalid(false);
            }
            case UsernameAvailabilityPresenter.Outcome.InvalidFormat ignored -> {
                field.setSuffixComponent(statusIcon(VaadinIcon.CLOSE_CIRCLE, "var(--lumo-error-color)"));
                field.setHelperText("3–32 characters · letters, digits, . _ -");
                field.setInvalid(true);
            }
            case UsernameAvailabilityPresenter.Outcome.AdminReserved ignored -> {
                field.setSuffixComponent(statusIcon(VaadinIcon.CLOSE_CIRCLE, "var(--lumo-error-color)"));
                field.setHelperText("Reserved for the platform-admin pool.");
                field.setInvalid(true);
            }
            case UsernameAvailabilityPresenter.Outcome.Taken ignored -> {
                field.setSuffixComponent(statusIcon(VaadinIcon.CLOSE_CIRCLE, "var(--lumo-error-color)"));
                field.setHelperText("Username taken — please choose another.");
                field.setInvalid(true);
            }
            case UsernameAvailabilityPresenter.Outcome.Available ignored -> {
                field.setSuffixComponent(statusIcon(VaadinIcon.CHECK_CIRCLE, "var(--lumo-success-color)"));
                field.setHelperText("Available");
                field.setInvalid(false);
            }
            case UsernameAvailabilityPresenter.Outcome.Failure fail -> {
                field.setSuffixComponent(statusIcon(VaadinIcon.WARNING, "var(--lumo-warning-color)"));
                field.setHelperText("Couldn't verify availability right now.");
                field.setInvalid(false);
            }
        }
    }

    private static Icon statusIcon(VaadinIcon glyph, String color) {
        Icon icon = glyph.create();
        icon.getStyle().set("color", color).set("width", "var(--lumo-icon-size-s)")
            .set("height", "var(--lumo-icon-size-s)");
        return icon;
    }

    private void attemptRegister(TextField username, EmailField email, IntegerField age,
                                 PasswordField password, PasswordField confirm) {
        if (username.isEmpty() || email.isEmpty() || age.isEmpty()
                || password.isEmpty() || confirm.isEmpty()) {
            Toasts.failure("Please fill in every field.");
            return;
        }
        if (!password.getValue().equals(confirm.getValue())) {
            Toasts.failure("Passwords don't match.");
            return;
        }
        if (AuthSession.isAdminUsername(username.getValue())) {
            Toasts.failure("That username is reserved for the platform-admin pool.");
            return;
        }

        RegisterPresenter.Outcome outcome = presenter.attemptRegister(
            username.getValue(),
            email.getValue(),
            password.getValue(),
            age.getValue(),
            GuestSession.sessionId()
        );

        switch (outcome) {
            case RegisterPresenter.Outcome.Success ignored -> {
                Toasts.success("Welcome, " + username.getValue() + "! Account created — please sign in.");
                UI.getCurrent().navigate(LoginView.class);
            }
            case RegisterPresenter.Outcome.UsernameTaken ignored ->
                Toasts.failure("Username taken — please choose another.");
            case RegisterPresenter.Outcome.EmailTaken ignored ->
                Toasts.failure("That email is already registered.");
            case RegisterPresenter.Outcome.WeakPassword weak ->
                Toasts.failure("That password is too weak — please choose a stronger one.");
            case RegisterPresenter.Outcome.InvalidEmail ignored ->
                Toasts.failure("That email address looks invalid.");
            case RegisterPresenter.Outcome.GuestSessionMissing miss -> {
                Toasts.warn("Your session timed out — reloading…");
                UI.getCurrent().getPage().reload();
            }
            case RegisterPresenter.Outcome.InvalidInput bad ->
                Toasts.failure("Please check your details and try again.");
            case RegisterPresenter.Outcome.Failure fail ->
                Toasts.failure("Registration failed — please try again.");
        }
    }
}
