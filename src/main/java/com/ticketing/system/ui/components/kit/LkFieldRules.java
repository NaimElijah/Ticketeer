package com.ticketing.system.ui.components.kit;

import com.vaadin.flow.component.textfield.PasswordField;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;

/**
 * Words the required rule a Vaadin text input checks itself (#189, WCAG 3.3.1). A required field the user
 * empties is marked invalid by its own validation, with a blank message unless its i18n says one, so the
 * field turns red and nothing says why. These set that message.
 *
 * <p>Each replaces the field's i18n, so give a field one call. A field with other rules of its own (a range,
 * a length, a format) words all of them in one i18n of its own type instead.
 */
public final class LkFieldRules {

    private LkFieldRules() {
    }

    /**
     * Makes the required {@code field} say {@code message} when it is empty.
     *
     * @param field   a field with {@code setRequired(true)}
     * @param message what to enter, e.g. "Enter a username."
     */
    public static void sayWhenEmpty(TextField field, String message) {
        field.setI18n(new TextField.TextFieldI18n().setRequiredErrorMessage(message));
    }

    /**
     * Makes the required {@code field} say {@code message} when it is empty.
     *
     * @param field   a field with {@code setRequired(true)}
     * @param message what to enter, e.g. "Enter a password."
     */
    public static void sayWhenEmpty(PasswordField field, String message) {
        field.setI18n(new PasswordField.PasswordFieldI18n().setRequiredErrorMessage(message));
    }

    /**
     * Makes the required {@code field} say {@code message} when it is empty.
     *
     * @param field   a field with {@code setRequired(true)}
     * @param message what to enter, e.g. "Enter a message."
     */
    public static void sayWhenEmpty(TextArea field, String message) {
        field.setI18n(new TextArea.TextAreaI18n().setRequiredErrorMessage(message));
    }
}
