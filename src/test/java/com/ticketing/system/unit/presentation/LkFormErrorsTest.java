package com.ticketing.system.unit.presentation;

import static com.ticketing.system.unit.presentation.ViewHarness.button;
import static com.ticketing.system.unit.presentation.ViewHarness.click;
import static com.ticketing.system.unit.presentation.ViewHarness.field;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.ui.components.kit.LkFormErrors;
import com.ticketing.system.ui.presenters.auth.RegisterPresenter;
import com.ticketing.system.ui.presenters.auth.UsernameAvailabilityPresenter;
import com.ticketing.system.ui.session.SessionIdentity;
import com.ticketing.system.ui.views.auth.RegisterView;
import com.vaadin.flow.component.textfield.EmailField;
import com.vaadin.flow.component.textfield.IntegerField;

/**
 * A refusal {@link LkFormErrors} put on a field gives way to the field's own validation once the user edits
 * it (#188, review C1; WCAG 3.3.1). A Vaadin field keeps a custom error message set on it and validates its
 * own constraints (an age's range, an email's form) on every value change, so a field refused as empty and
 * then given a value that breaks its own constraint must not go on showing "Enter your age.", and the next
 * submit's {@link LkFormErrors#clear()} must not unmark a field that is still invalid by its own rule. A
 * field the user corrects shows no message left over from the refusal.
 */
class LkFormErrorsTest {

    // ---- review C1: a refusal gives way to the field's own validation (#188) ----

    private static final String ENTER_AGE = "Enter your age.";
    private static final String ENTER_EMAIL = "Enter your email address.";
    private static final int MIN_AGE = 1;
    private static final int MAX_AGE = 120;
    private static final int AGE_OVER_MAX = 150;

    private ViewHarness harness;

    @BeforeEach
    void setUp() {
        harness = new ViewHarness();
    }

    @AfterEach
    void tearDown() {
        harness.close();
    }

    // ---- a value that breaks the field's own constraint ----

    @Test
    void GivenARefusedAgeField_WhenTheUserEntersAnAgeAboveItsMaximum_ThenItIsInvalidAndTheRefusalIsGone() {
        IntegerField age = ageField();
        new LkFormErrors().reject(age, ENTER_AGE);

        age.setValue(AGE_OVER_MAX);

        assertTrue(age.isInvalid(), "an age above " + MAX_AGE + " breaks the field's own range");
        assertNotEquals(ENTER_AGE, age.getErrorMessage(), "the field now holds an age, so it must not still say \""
            + ENTER_AGE + "\"");
    }

    @Test
    void GivenARefusedAgeFieldNowAboveItsMaximum_WhenTheNextSubmitClearsTheRefusals_ThenTheFieldStaysInvalid() {
        IntegerField age = ageField();
        LkFormErrors errors = new LkFormErrors();
        errors.reject(age, ENTER_AGE);
        age.setValue(AGE_OVER_MAX);

        errors.clear();

        assertTrue(age.isInvalid(), "clearing the last submit's refusals must not unmark an age that is still out "
            + "of range by the field's own rule");
    }

    @Test
    void GivenARefusedEmailField_WhenTheUserEntersAMalformedAddress_ThenItIsInvalidAndTheRefusalIsGone() {
        EmailField email = new EmailField("Email");
        new LkFormErrors().reject(email, ENTER_EMAIL);

        email.setValue("bob@");

        assertTrue(email.isInvalid(), "\"bob@\" breaks the email field's own format");
        assertNotEquals(ENTER_EMAIL, email.getErrorMessage(), "the field now holds an address, so it must not still "
            + "say \"" + ENTER_EMAIL + "\"");
    }

    @Test
    void GivenARefusedEmailFieldNowMalformed_WhenTheNextSubmitClearsTheRefusals_ThenTheFieldStaysInvalid() {
        EmailField email = new EmailField("Email");
        LkFormErrors errors = new LkFormErrors();
        errors.reject(email, ENTER_EMAIL);
        email.setValue("bob@");

        errors.clear();

        assertTrue(email.isInvalid(), "clearing the last submit's refusals must not unmark an address that is still "
            + "malformed by the field's own rule");
    }

    // ---- a value that corrects the field ----

    @Test
    void GivenARefusedAgeField_WhenTheUserEntersAValidAge_ThenItIsValidWithNoStaleMessage() {
        IntegerField age = ageField();
        new LkFormErrors().reject(age, ENTER_AGE);

        age.setValue(30);

        assertFalse(age.isInvalid(), "a valid age clears the refusal");
        assertNotEquals(ENTER_AGE, age.getErrorMessage(), "a corrected field must keep no message from the refusal, "
            + "which it would show again the moment its own rule marks it invalid");
    }

    // ---- through RegisterView ----

    @Test
    void GivenRegistrationRefusedForAnEmptyAge_WhenTheUserEntersAnAgeAboveTheMaximum_ThenTheAgeIsInvalidAndTheRefusalIsGone() {
        UsernameAvailabilityPresenter availability = mock(UsernameAvailabilityPresenter.class);
        when(availability.check(any())).thenReturn(new UsernameAvailabilityPresenter.Outcome.Available());
        RegisterView view = new RegisterView(mock(RegisterPresenter.class), availability, mock(SessionIdentity.class));
        harness.attach(view);
        click(button(view, "Create account"));
        IntegerField age = field(view, IntegerField.class, "Age");
        String refusal = age.getErrorMessage();
        assertTrue(age.isInvalid() && refusal != null && !refusal.isBlank(), "an empty age is refused on submit");

        age.setValue(AGE_OVER_MAX);

        assertTrue(age.isInvalid(), "an age above the field's maximum is invalid by its own rule");
        assertNotEquals(refusal, age.getErrorMessage(), "the age now holds a value, so it must not still say \""
            + refusal + "\"");
    }

    private static IntegerField ageField() {
        IntegerField age = new IntegerField("Age");
        age.setMin(MIN_AGE);
        age.setMax(MAX_AGE);
        return age;
    }
}
