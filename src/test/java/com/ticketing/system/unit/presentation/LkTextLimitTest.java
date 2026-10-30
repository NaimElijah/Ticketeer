package com.ticketing.system.unit.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.ticketing.system.shared.domain.TextLimits;
import com.ticketing.system.ui.components.kit.LkTextLimit;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.component.textfield.TextFieldBase;
import com.vaadin.flow.data.value.ValueChangeMode;

/**
 * {@link LkTextLimit} tells the user about a length limit instead of enforcing it with
 * {@code maxlength}, which cuts a paste short without a word (WCAG 3.3.1). Constructed headless:
 * a server-side {@code setValue} fires the same value-change listeners a client edit does.
 */
class LkTextLimitTest {

    private static final int LIMIT = 10;
    private static final int RAISED_LIMIT = 20;
    private static final int LOWERED_LIMIT = 4;
    private static final String WITHIN = "a".repeat(LIMIT);
    private static final String OVER = "a".repeat(LIMIT + 2);

    @Test
    void GivenALimitedField_WhenTheValueGoesOverTheLimit_ThenTheFieldIsInvalidAndSaysByHowMuch() {
        TextField field = new TextField("Subject");
        LkTextLimit.apply(field, LIMIT);

        field.setValue(OVER);

        assertTrue(field.isInvalid());
        assertEquals("At most 10 characters; this has 12.", field.getErrorMessage());
        assertTrue(LkTextLimit.exceeds(field));
    }

    @Test
    void GivenAValueExactlyAtTheLimit_WhenSet_ThenTheFieldStaysValid() {
        TextField field = new TextField("Subject");
        LkTextLimit.apply(field, LIMIT);

        field.setValue(WITHIN);

        assertFalse(field.isInvalid());
        assertFalse(LkTextLimit.exceeds(field));
    }

    @Test
    void GivenAnOverLimitValue_WhenTheValueIsBackWithinTheLimit_ThenTheErrorIsCleared() {
        TextField field = new TextField("Subject");
        LkTextLimit.apply(field, LIMIT);
        field.setValue(OVER);

        field.setValue(WITHIN);

        assertFalse(field.isInvalid());
        assertNoErrorMessage(field);
        assertFalse(LkTextLimit.exceeds(field));
    }

    @Test
    void GivenAnOverLimitValue_WhenTheLimitIsRaisedAboveIt_ThenTheErrorIsClearedAtOnce() {
        TextField field = new TextField("Subject");
        LkTextLimit limit = LkTextLimit.apply(field, LIMIT);
        field.setValue(OVER);

        limit.setMax(RAISED_LIMIT);

        assertFalse(field.isInvalid());
        assertNoErrorMessage(field);
        assertFalse(LkTextLimit.exceeds(field));
    }

    @Test
    void GivenAValueWithinTheLimit_WhenTheLimitIsLoweredBelowIt_ThenTheFieldIsFlaggedAtOnce() {
        TextArea field = new TextArea("Details");
        LkTextLimit limit = LkTextLimit.apply(field, LIMIT);
        field.setValue(WITHIN);

        limit.setMax(LOWERED_LIMIT);

        assertTrue(field.isInvalid());
        assertEquals("At most 4 characters; this has 10.", field.getErrorMessage());
        assertTrue(LkTextLimit.exceeds(field));
    }

    @Test
    void GivenALimit_WhenApplied_ThenTheHelperTextNamesIt() {
        TextField field = new TextField("Subject");

        LkTextLimit.apply(field, LIMIT);

        assertEquals("Up to 10 characters", field.getHelperText());
    }

    @Test
    void GivenALimitedField_WhenTheLimitChanges_ThenTheHelperTextNamesTheNewLimit() {
        TextField field = new TextField("Subject");
        LkTextLimit limit = LkTextLimit.apply(field, LIMIT);

        limit.setMax(RAISED_LIMIT);

        assertEquals("Up to 20 characters", field.getHelperText());
    }

    @Test
    void GivenALimitedField_WhenTheLimitIsAppliedAgain_ThenTheNewLimitReplacesTheOld() {
        TextField field = new TextField("Subject");
        LkTextLimit.apply(field, LIMIT);
        field.setValue(OVER);

        LkTextLimit.apply(field, RAISED_LIMIT);

        assertFalse(field.isInvalid());
        assertEquals("Up to 20 characters", field.getHelperText());
    }

    // maxlength makes the browser drop whatever goes past it, and the cut text then saves.
    @Test
    void GivenALimit_WhenApplied_ThenTheFieldCarriesNoMaxlength() {
        TextField field = new TextField("Subject");
        TextArea area = new TextArea("Details");

        LkTextLimit.apply(field, LIMIT);
        LkTextLimit.apply(area, LIMIT);

        assertFalse(field.getElement().hasProperty("maxlength"));
        assertFalse(area.getElement().hasProperty("maxlength"));
    }

    // The user hears that the text is too long while typing, not only when the field loses focus.
    @Test
    void GivenALimit_WhenApplied_ThenTheValueIsCheckedAsTheUserTypes() {
        TextArea field = new TextArea("Details");

        LkTextLimit.apply(field, LIMIT);

        assertEquals(ValueChangeMode.EAGER, field.getValueChangeMode());
    }

    @Test
    void GivenNoExplicitLimit_WhenApplied_ThenTheColumnWidthIsTheLimit() {
        TextField field = new TextField("Subject");
        LkTextLimit.apply(field);

        field.setValue("a".repeat(TextLimits.MAX_TEXT_LENGTH));
        assertFalse(field.isInvalid());

        field.setValue("a".repeat(TextLimits.MAX_TEXT_LENGTH + 1));
        assertTrue(field.isInvalid());
        assertEquals("Up to " + TextLimits.MAX_TEXT_LENGTH + " characters", field.getHelperText());
    }

    // Clearing an over-limit required field must leave the field's own "required" verdict in place.
    @Test
    void GivenARequiredFieldOverTheLimit_WhenItIsEmptied_ThenItStaysInvalidForBeingRequired() {
        TextField field = new TextField("Subject");
        field.setRequired(true);
        LkTextLimit.apply(field, LIMIT);
        field.setValue(OVER);

        field.setValue("");

        assertTrue(field.isInvalid());
        assertFalse(LkTextLimit.exceeds(field));
        assertNoErrorMessage(field);
    }

    @Test
    void GivenAFieldWithoutALimit_WhenExceedsIsAsked_ThenItIsFalse() {
        TextField field = new TextField("Subject");
        field.setValue(OVER);

        assertFalse(LkTextLimit.exceeds(field));
    }

    @Test
    void GivenANegativeLimit_WhenApplied_ThenItIsRefused() {
        TextField field = new TextField("Subject");

        assertThrows(IllegalArgumentException.class, () -> LkTextLimit.apply(field, -1));
    }

    private static void assertNoErrorMessage(TextFieldBase<?, String> field) {
        String message = field.getErrorMessage();
        assertTrue(message == null || message.isEmpty(), "expected no error message, got: " + message);
    }
}
