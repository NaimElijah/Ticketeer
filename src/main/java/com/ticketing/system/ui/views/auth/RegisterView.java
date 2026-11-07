package com.ticketing.system.ui.views.auth;

import com.ticketing.system.ui.components.Toasts;
import com.ticketing.system.ui.components.auth.PasswordStrengthMeter;
import com.ticketing.system.ui.components.kit.Lk;
import com.ticketing.system.ui.components.kit.LkAuthCard;
import com.ticketing.system.ui.components.kit.LkFormErrors;
import com.ticketing.system.ui.layouts.MainLayout;
import com.ticketing.system.ui.presenters.auth.RegisterPresenter;
import com.ticketing.system.ui.presenters.auth.UsernameAvailabilityPresenter;
import com.ticketing.system.ui.session.SessionIdentity;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.Focusable;
import com.vaadin.flow.component.HasValue;
import com.vaadin.flow.component.Key;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.shared.HasValidationProperties;
import com.vaadin.flow.component.textfield.Autocomplete;
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
    private static final String USERNAME_TAKEN = "Username taken — please choose another.";
    private static final String USERNAME_RESERVED = "That username is reserved — please choose another.";
    private static final String PASSWORDS_DIFFER = "Passwords don't match.";
    private static final String USERNAME_FORMAT = "3–32 characters · letters, digits, . _ -";
    private static final String AVAILABILITY_UNKNOWN = "Couldn't verify availability right now.";

    private final RegisterPresenter presenter;
    private final UsernameAvailabilityPresenter usernameAvailability;
    // The guest id comes from here, not GuestSession: a guest session expires a fixed time after it
    // starts, and SessionIdentity replaces a dead one before registration is given it.
    private final SessionIdentity sessionIdentity;
    private final LkFormErrors errors = new LkFormErrors();
    /** Says the username check's result as it arrives (#188, WCAG 4.1.3). */
    private final Span availabilityStatus = Lk.asStatus(new Span());

    public RegisterView(RegisterPresenter presenter,
                        UsernameAvailabilityPresenter usernameAvailability,
                        SessionIdentity sessionIdentity) {
        super("Create your account",
              "Join TicketHub to discover events and book in seconds.");
        this.presenter = presenter;
        this.usernameAvailability = usernameAvailability;
        this.sessionIdentity = sessionIdentity;

        // The autocomplete tokens let a browser or password manager fill the fields (#188, WCAG 1.3.5).
        TextField username = new TextField("Username");
        username.setPlaceholder("3–32 characters, letters and digits");
        username.setHelperText(USERNAME_HELP_DEFAULT);
        username.setRequired(true);
        username.setAutocomplete(Autocomplete.USERNAME);
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
        email.setAutocomplete(Autocomplete.EMAIL);
        email.setMaxLength(RegisterPresenter.MAX_EMAIL_LENGTH);
        email.setWidthFull();

        IntegerField age = new IntegerField("Age");
        age.setMin(RegisterPresenter.MIN_AGE);
        age.setMax(RegisterPresenter.MAX_AGE);
        age.setStepButtonsVisible(true);
        age.setRequiredIndicatorVisible(true);
        age.setWidthFull();

        PasswordField password = new PasswordField("Password");
        password.setPlaceholder("At least 8 characters");
        password.setRequired(true);
        // new-password, on both fields, so a password manager offers to generate one and doesn't fill a stored one.
        password.setAutocomplete(Autocomplete.NEW_PASSWORD);
        password.setWidthFull();
        // Live strength feedback. EAGER mode fires on every keystroke so the
        // bar and rule chips track the user's input directly.
        PasswordStrengthMeter strengthMeter = new PasswordStrengthMeter();
        password.setValueChangeMode(ValueChangeMode.EAGER);
        password.addValueChangeListener(e -> strengthMeter.update(e.getValue()));

        PasswordField confirm = new PasswordField("Confirm password");
        confirm.setPlaceholder("Re-enter your password");
        confirm.setRequired(true);
        confirm.setAutocomplete(Autocomplete.NEW_PASSWORD);
        confirm.setWidthFull();

        Button create = new Button("Create account",
            e -> attemptRegister(username, email, age, password, confirm));
        create.addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_LARGE);
        create.setWidthFull();
        // Enter in a field registers; on the page it would also take over Enter on the link below (#188).
        create.addClickShortcut(Key.ENTER).listenOn(username, email, age, password, confirm);

        // The check's result lands in the field's helper text, which a screen reader reads only on reaching the
        // field; this status says it as it arrives. Heard, not shown, since the helper text shows it (#188).
        availabilityStatus.addClassName(Lk.VISUALLY_HIDDEN);

        col(14, username, availabilityStatus, email, age, password, strengthMeter, confirm, create);
        foot("Already have an account?", "Sign in", LoginView.class);
    }

    private void applyUsernameFeedback(TextField field, String value) {
        UsernameAvailabilityPresenter.Outcome outcome = usernameAvailability.check(value);
        // Taken and reserved show as the field's error message, which the field announces itself.
        availabilityStatus.setText(switch (outcome) {
            case UsernameAvailabilityPresenter.Outcome.Available ignored -> "Username " + value + " is available";
            case UsernameAvailabilityPresenter.Outcome.InvalidFormat ignored -> USERNAME_FORMAT;
            case UsernameAvailabilityPresenter.Outcome.Failure ignored -> AVAILABILITY_UNKNOWN;
            case UsernameAvailabilityPresenter.Outcome.Empty ignored -> "";
            case UsernameAvailabilityPresenter.Outcome.Taken ignored -> "";
            case UsernameAvailabilityPresenter.Outcome.AdminReserved ignored -> "";
        });
        switch (outcome) {
            case UsernameAvailabilityPresenter.Outcome.Empty ignored -> {
                field.setSuffixComponent(null);
                field.setHelperText(USERNAME_HELP_DEFAULT);
                field.setInvalid(false);
            }
            case UsernameAvailabilityPresenter.Outcome.InvalidFormat ignored -> {
                field.setSuffixComponent(statusIcon(VaadinIcon.CLOSE_CIRCLE, "var(--lumo-error-color)"));
                field.setHelperText(USERNAME_FORMAT);
                field.setErrorMessage(null);
                field.setInvalid(true);
            }
            // An error message, not helper text, so assistive technology announces it with the invalid state.
            case UsernameAvailabilityPresenter.Outcome.AdminReserved ignored -> {
                field.setSuffixComponent(statusIcon(VaadinIcon.CLOSE_CIRCLE, "var(--lumo-error-color)"));
                field.setHelperText(null);
                field.setErrorMessage("Reserved — please choose another.");
                field.setInvalid(true);
            }
            case UsernameAvailabilityPresenter.Outcome.Taken ignored -> {
                field.setSuffixComponent(statusIcon(VaadinIcon.CLOSE_CIRCLE, "var(--lumo-error-color)"));
                field.setHelperText(null);
                field.setErrorMessage(USERNAME_TAKEN);
                field.setInvalid(true);
            }
            case UsernameAvailabilityPresenter.Outcome.Available ignored -> {
                field.setSuffixComponent(statusIcon(VaadinIcon.CHECK_CIRCLE, "var(--lumo-success-color)"));
                field.setHelperText("Available");
                field.setInvalid(false);
            }
            case UsernameAvailabilityPresenter.Outcome.Failure fail -> {
                field.setSuffixComponent(statusIcon(VaadinIcon.WARNING, "var(--lumo-warning-color)"));
                field.setHelperText(AVAILABILITY_UNKNOWN);
                field.setInvalid(false);
            }
        }
    }

    private <F extends Component & HasValue<?, ?> & HasValidationProperties & Focusable<?>> void rejectIfEmpty(
            F field, String message) {
        if (field.isEmpty()) {
            errors.reject(field, message);
        }
    }

    // The toast stays, but the refused fields now carry the message too, and the first takes focus (#188).
    private void refuse(String toast) {
        Toasts.failure(toast);
        errors.focusFirst();
    }

    // A refusal the registration service gives for one field: that field shows it and takes focus (#188).
    private <F extends Component & HasValidationProperties & Focusable<?>> void refuse(F field, String message) {
        errors.reject(field, message);
        refuse(message);
    }

    private static Icon statusIcon(VaadinIcon glyph, String color) {
        Icon icon = glyph.create();
        icon.getStyle().set("color", color).set("width", "var(--lumo-icon-size-s)")
            .set("height", "var(--lumo-icon-size-s)");
        return icon;
    }

    private void attemptRegister(TextField username, EmailField email, IntegerField age,
                                 PasswordField password, PasswordField confirm) {
        errors.clear();
        rejectIfEmpty(username, "Enter a username.");
        rejectIfEmpty(email, "Enter your email address.");
        rejectIfEmpty(age, "Enter your age.");
        rejectIfEmpty(password, "Enter a password.");
        rejectIfEmpty(confirm, "Enter your password again.");
        if (!errors.isEmpty()) {
            refuse("Please fill in every field.");
            return;
        }
        if (!password.getValue().equals(confirm.getValue())) {
            errors.reject(confirm, PASSWORDS_DIFFER);
            refuse(PASSWORDS_DIFFER);
            return;
        }
        if (usernameAvailability.isReserved(username.getValue())) {
            errors.reject(username, USERNAME_RESERVED);
            refuse(USERNAME_RESERVED);
            return;
        }

        RegisterPresenter.Outcome outcome = presenter.attemptRegister(
            username.getValue(),
            email.getValue(),
            password.getValue(),
            age.getValue(),
            sessionIdentity.guestSessionId()
        );

        switch (outcome) {
            case RegisterPresenter.Outcome.Success ignored -> {
                Toasts.success("Welcome, " + username.getValue() + "! Account created — please sign in.");
                UI.getCurrent().navigate(LoginView.class);
            }
            case RegisterPresenter.Outcome.UsernameTaken ignored -> refuse(username, USERNAME_TAKEN);
            case RegisterPresenter.Outcome.EmailTaken ignored -> refuse(email, "That email is already registered.");
            case RegisterPresenter.Outcome.WeakPassword weak ->
                refuse(password, Lk.withReason("That password is too weak", weak.reason()));
            case RegisterPresenter.Outcome.InvalidEmail ignored ->
                refuse(email, "That email address looks invalid.");
            case RegisterPresenter.Outcome.GuestSessionMissing miss -> {
                Toasts.warn("Your session timed out — reloading…");
                UI.getCurrent().getPage().reload();
            }
            case RegisterPresenter.Outcome.InvalidInput bad ->
                Toasts.failure(Lk.withReason("Please check your details", bad.reason()));
            case RegisterPresenter.Outcome.Failure fail ->
                Toasts.failure(Lk.withReason("Registration failed", fail.reason()));
        }
    }
}
