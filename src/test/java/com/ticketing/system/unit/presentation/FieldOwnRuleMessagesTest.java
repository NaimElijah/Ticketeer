package com.ticketing.system.unit.presentation;

import static com.ticketing.system.unit.presentation.ViewHarness.field;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.ticketing.system.ui.presenters.auth.RegisterPresenter;
import com.ticketing.system.ui.presenters.auth.UsernameAvailabilityPresenter;
import com.ticketing.system.ui.session.SessionIdentity;
import com.ticketing.system.ui.views.auth.RegisterView;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.HasValue;
import com.vaadin.flow.component.shared.HasValidationProperties;
import com.vaadin.flow.component.textfield.EmailField;
import com.vaadin.flow.component.textfield.IntegerField;

/**
 * A field that its own rule (required, a range, a length, a format) marks invalid says what is wrong (#189,
 * WCAG 3.3.1). A Vaadin field validates itself on every change, and without i18n text for the rule it breaks
 * it turns red with a blank message. Each test attaches the page, gives one field a value its own rule refuses,
 * and checks that the field is invalid and says why.
 */
class FieldOwnRuleMessagesTest {

    private static final String TYPED = "x";
    // Well formed, and one character over the 254 registration accepts.
    private static final String EMAIL_OF_255_CHARACTERS = "a".repeat(64) + "@" + "b".repeat(186) + ".com";

    private ViewHarness harness;

    @BeforeEach
    void setUp() {
        harness = new ViewHarness();
    }

    @AfterEach
    void tearDown() {
        harness.close();
    }

    // ---- RegisterView ----

    @ParameterizedTest
    @ValueSource(strings = {"Password", "Confirm password"})
    void GivenTheRegisterPage_WhenARequiredTextFieldIsEmptied_ThenItSaysWhatToEnter(String label) {
        Component field = field(registerPage(), Component.class, label);

        typeThenEmpty(field);

        assertSaysWhatIsWrong(field, label);
    }

    @Test
    void GivenTheRegisterPage_WhenTheEmailIsEmptied_ThenItSaysWhatToEnter() {
        EmailField email = field(registerPage(), EmailField.class, "Email");

        typeThenEmpty(email);

        assertSaysWhatIsWrong(email, "Email");
    }

    @Test
    void GivenTheRegisterPage_WhenTheEmailIsLongerThanItsMaximum_ThenItSaysWhatIsWrong() {
        EmailField email = field(registerPage(), EmailField.class, "Email");

        email.setValue(EMAIL_OF_255_CHARACTERS);

        assertSaysWhatIsWrong(email, "an email of 255 characters");
    }

    @Test
    void GivenTheRegisterPage_WhenTheAgeIsBelowItsMinimum_ThenItSaysWhatIsWrong() {
        IntegerField age = field(registerPage(), IntegerField.class, "Age");

        age.setValue(RegisterPresenter.MIN_AGE - 1);

        assertSaysWhatIsWrong(age, "an age below the minimum");
    }

    @Test
    void GivenTheRegisterPage_WhenTheAgeIsEmptied_ThenItSaysWhatToEnter() {
        IntegerField age = field(registerPage(), IntegerField.class, "Age");
        age.setValue(RegisterPresenter.MIN_AGE);

        age.clear();

        assertSaysWhatIsWrong(age, "an emptied age");
    }

    private RegisterView registerPage() {
        UsernameAvailabilityPresenter availability = mock(UsernameAvailabilityPresenter.class);
        when(availability.check(any())).thenReturn(new UsernameAvailabilityPresenter.Outcome.Empty());
        RegisterView view = new RegisterView(mock(RegisterPresenter.class), availability, mock(SessionIdentity.class));
        harness.attach(view);
        return view;
    }

    // ---- shared ----

    @SuppressWarnings("unchecked") // every field these tests type into holds text
    private static void typeThenEmpty(Component field) {
        HasValue<?, String> text = (HasValue<?, String>) field;
        text.setValue(TYPED);
        text.clear();
    }

    private static void assertSaysWhatIsWrong(Component component, String what) {
        HasValidationProperties field = (HasValidationProperties) component;
        assertTrue(field.isInvalid(), what + " must be invalid by the field's own rule");
        String message = field.getErrorMessage();
        assertTrue(message != null && !message.isBlank(),
            what + " must say what is wrong; its message is \"" + message + "\"");
    }
}
