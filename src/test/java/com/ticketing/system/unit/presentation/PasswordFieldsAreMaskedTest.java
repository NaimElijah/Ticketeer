package com.ticketing.system.unit.presentation;

import static com.ticketing.system.unit.presentation.ViewHarness.find;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.ui.presenters.auth.AdminLoginPresenter;
import com.ticketing.system.ui.presenters.auth.LoginPresenter;
import com.ticketing.system.ui.presenters.auth.RegisterPresenter;
import com.ticketing.system.ui.presenters.auth.UsernameAvailabilityPresenter;
import com.ticketing.system.ui.session.SessionIdentity;
import com.ticketing.system.ui.views.auth.AdminLoginView;
import com.ticketing.system.ui.views.auth.LoginView;
import com.ticketing.system.ui.views.auth.RegisterView;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.HasValue;
import com.vaadin.flow.component.textfield.PasswordField;

/**
 * Passwords are masked in the UI (SLR.2). Every input on the sign-in, admin sign-in and registration forms
 * that asks for a password is a {@link PasswordField}, which the browser masks as it is typed; a text field
 * labelled as one would show the password to anyone looking at the screen.
 */
class PasswordFieldsAreMaskedTest {

    private static final String PASSWORD = "password";

    private ViewHarness harness;

    @BeforeEach
    void setUp() {
        harness = new ViewHarness();
    }

    @AfterEach
    void tearDown() {
        harness.close();
    }

    @Test
    void GivenTheSignInForm_WhenItRenders_ThenItsPasswordInputIsAMaskedPasswordField() {
        assertEveryPasswordInputIsMasked(new LoginView(mock(LoginPresenter.class), mock(SessionIdentity.class)),
            List.of("Password"));
    }

    @Test
    void GivenTheAdminSignInForm_WhenItRenders_ThenItsPasswordInputIsAMaskedPasswordField() {
        assertEveryPasswordInputIsMasked(new AdminLoginView(mock(AdminLoginPresenter.class)), List.of("Password"));
    }

    @Test
    void GivenTheRegistrationForm_WhenItRenders_ThenBothPasswordInputsAreMaskedPasswordFields() {
        assertEveryPasswordInputIsMasked(register(), List.of("Password", "Confirm password"));
    }

    // Finds the inputs by their label, whatever their type, so one built as a text field is found too.
    private static void assertEveryPasswordInputIsMasked(Component form, List<String> expectedLabels) {
        List<Component> passwordInputs = find(form, Component.class).stream()
            .filter(component -> component instanceof HasValue<?, ?>)
            .filter(component -> labelOf(component).toLowerCase(Locale.ROOT).contains(PASSWORD))
            .toList();

        assertEquals(expectedLabels, passwordInputs.stream().map(PasswordFieldsAreMaskedTest::labelOf).toList(),
            "the inputs on the form that ask for a password");
        for (Component input : passwordInputs) {
            assertInstanceOf(PasswordField.class, input, "\"" + labelOf(input)
                + "\" asks for a password, so it must be a masked PasswordField, but is a "
                + input.getClass().getSimpleName());
        }
    }

    private static String labelOf(Component component) {
        return component.getElement().getProperty("label", "");
    }

    private static RegisterView register() {
        UsernameAvailabilityPresenter availability = mock(UsernameAvailabilityPresenter.class);
        when(availability.check(any())).thenReturn(new UsernameAvailabilityPresenter.Outcome.Available());
        return new RegisterView(mock(RegisterPresenter.class), availability, mock(SessionIdentity.class));
    }
}
