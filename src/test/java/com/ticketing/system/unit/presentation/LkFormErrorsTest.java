package com.ticketing.system.unit.presentation;

import static com.ticketing.system.unit.presentation.ViewHarness.button;
import static com.ticketing.system.unit.presentation.ViewHarness.click;
import static com.ticketing.system.unit.presentation.ViewHarness.field;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
import com.ticketing.system.ui.components.kit.LkTextLimit;
import com.ticketing.system.ui.presenters.auth.RegisterPresenter;
import com.ticketing.system.ui.presenters.auth.UsernameAvailabilityPresenter;
import com.ticketing.system.ui.session.SessionIdentity;
import com.ticketing.system.ui.views.auth.RegisterView;
import com.vaadin.flow.component.textfield.EmailField;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.TextField;

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
    private static final String ENTER_NAME = "Enter a name.";
    private static final int NAME_LIMIT = 5;
    private static final String ENTER_EMAIL = "Enter your email address.";
    private static final int MIN_AGE = 1;
    private static final int MAX_AGE = 120;
    private static final int AGE_OVER_MAX = 150;
    private static final String AGE_REFUSED = "Please enter your age.";

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

    // clear() keeps a field it leaves invalid, to hand it back again on its next edit, but the submit that
    // called it has refused nothing yet.
    @Test
    void GivenAnAgeAboveItsMaximumWasRefused_WhenTheNextSubmitClearsTheRefusals_ThenNoFieldCountsAsRefused() {
        IntegerField age = ageField();
        age.setValue(AGE_OVER_MAX);
        LkFormErrors errors = new LkFormErrors();
        errors.reject(age, AGE_REFUSED);

        errors.clear();

        assertTrue(age.isInvalid(), "precondition: the age is still invalid by its own rule");
        assertTrue(errors.isEmpty(), "a field the submit has not refused must not count as refused");
    }

    // ---- a message another check set ----

    // LkFormErrors' contract: clear() leaves a field whose message changed since its refusal, here the text
    // limit's, which still holds for the text (#189).
    @Test
    void GivenARefusedFieldNowOverItsTextLimit_WhenTheNextSubmitClearsTheRefusals_ThenTheLimitsErrorStays() {
        TextField name = new TextField("Name");
        LkTextLimit.apply(name, NAME_LIMIT);
        LkFormErrors errors = new LkFormErrors();
        errors.reject(name, ENTER_NAME);
        name.setValue("x".repeat(NAME_LIMIT + 1));
        String limitError = name.getErrorMessage();
        assertTrue(name.isInvalid() && limitError != null && !limitError.isBlank() && !ENTER_NAME.equals(limitError),
            "precondition: the text limit flags the name with its own message, not \"" + limitError + "\"");

        errors.clear();

        assertTrue(name.isInvalid(), "the name is still over its limit");
        assertEquals(limitError, name.getErrorMessage(), "the limit's message must stay");
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

    // ---- a field handed back to its own rule says what is wrong (#189) ----
    //
    // After a submit refuses the form, LkFormErrors hands each refused field back to its own rule once the user
    // edits it. The age's range and the email's format then mark the field invalid, and the field must say
    // why rather than turn red with a blank message (WCAG 3.3.1): RegisterView words those rules (setI18n),
    // and the hand-back keeps the message the rule gives.

    @Test
    void GivenRegistrationRefusedForAnEmptyAge_WhenTheUserEntersAnAgeAboveTheMaximum_ThenTheAgeSaysWhatIsWrong() {
        RegisterView view = registerView();
        click(button(view, "Create account"));
        IntegerField age = field(view, IntegerField.class, "Age");

        age.setValue(AGE_OVER_MAX);

        assertTrue(age.isInvalid(), "an age above the field's maximum is invalid by its own rule");
        String message = age.getErrorMessage();
        assertTrue(message != null && !message.isBlank(),
            "an invalid age must say what is wrong with it; its message is \"" + message + "\"");
    }

    @Test
    void GivenRegistrationRefusedForAnEmptyEmail_WhenTheUserEntersAMalformedAddress_ThenTheEmailSaysWhatIsWrong() {
        RegisterView view = registerView();
        click(button(view, "Create account"));
        EmailField email = field(view, EmailField.class, "Email");

        email.setValue("bob@");

        assertTrue(email.isInvalid(), "\"bob@\" breaks the email field's own format");
        String message = email.getErrorMessage();
        assertTrue(message != null && !message.isBlank(),
            "an invalid email must say what is wrong with it; its message is \"" + message + "\"");
    }

    private RegisterView registerView() {
        UsernameAvailabilityPresenter availability = mock(UsernameAvailabilityPresenter.class);
        when(availability.check(any())).thenReturn(new UsernameAvailabilityPresenter.Outcome.Available());
        RegisterView view = new RegisterView(mock(RegisterPresenter.class), availability, mock(SessionIdentity.class));
        harness.attach(view);
        return view;
    }

    private static IntegerField ageField() {
        IntegerField age = new IntegerField("Age");
        age.setMin(MIN_AGE);
        age.setMax(MAX_AGE);
        return age;
    }
}
