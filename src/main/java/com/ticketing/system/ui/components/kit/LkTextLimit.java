package com.ticketing.system.ui.components.kit;

import com.ticketing.system.shared.domain.TextLimits;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.textfield.TextFieldBase;
import com.vaadin.flow.data.value.ValueChangeMode;

/**
 * A length limit on a {@code TextField} or {@code TextArea} that tells the user instead of
 * truncating. {@code maxlength} drops whatever a paste puts past it without a word, and the cut
 * text then saves (WCAG 3.3.1); this names the limit in the helper text (3.3.2) and, while the text
 * is over it, marks the field invalid with an error message saying by how much. The text itself is
 * left alone, so the user decides what to cut. A view refuses submit with {@link #exceeds}; the
 * domain refuses the text too ({@link TextLimits}), so this is guidance, not the guard.
 *
 * <p>Length is {@link String#length()}, as the domain counts it.
 *
 * <p>The check runs on every keystroke ({@link ValueChangeMode#EAGER}), so the error appears while
 * the user types rather than on blur. It relies on the field's own validation listener, which Vaadin
 * registers in the field's constructor, running before this one: on a value within its own
 * constraints that listener clears {@code invalid}, and this one, running after, sets it again.
 */
public final class LkTextLimit {

    private final TextFieldBase<?, String> field;
    private int max;
    /** Whether this limit, rather than the field's own validation, put the field in error. */
    private boolean flagged;

    private LkTextLimit(TextFieldBase<?, String> field) {
        this.field = field;
    }

    /**
     * Limits {@code field} to {@link TextLimits#MAX_TEXT_LENGTH}, the width of the text columns.
     *
     * @see #apply(TextFieldBase, int)
     */
    public static <F extends TextFieldBase<F, String>> LkTextLimit apply(F field) {
        return apply(field, TextLimits.MAX_TEXT_LENGTH);
    }

    /**
     * Limits {@code field} to {@code max} characters and checks its current value at once. Applying
     * a limit to a field that already has one replaces it, as {@link #setMax(int)} does.
     *
     * <p>Side effects on the field: sets its helper text and switches it to
     * {@link ValueChangeMode#EAGER}. Never sets {@code maxlength}.
     *
     * @param field the field to limit; not {@code null}
     * @param max   the most characters the value may have; not negative
     * @return the limit, through which the view can change {@code max} later
     * @throws IllegalArgumentException if {@code max} is negative
     */
    public static <F extends TextFieldBase<F, String>> LkTextLimit apply(F field, int max) {
        LkTextLimit existing = ComponentUtil.getData(field, LkTextLimit.class);
        if (existing != null) {
            existing.setMax(max);
            return existing;
        }
        LkTextLimit limit = new LkTextLimit(field);
        limit.setMax(max);
        ComponentUtil.setData(field, LkTextLimit.class, limit);
        field.setValueChangeMode(ValueChangeMode.EAGER);
        field.addValueChangeListener(event -> limit.check());
        return limit;
    }

    /**
     * Changes the limit and re-checks the current value at once, so text over a lowered limit is
     * flagged without waiting for the next keystroke, and text within a raised one is cleared.
     *
     * @param max the most characters the value may have; not negative
     * @throws IllegalArgumentException if {@code max} is negative
     */
    public void setMax(int max) {
        if (max < 0) {
            throw new IllegalArgumentException("A text limit cannot be negative, was " + max);
        }
        this.max = max;
        field.setHelperText("Up to " + max + " characters");
        check();
    }

    /**
     * Whether {@code field}'s current value is longer than the limit applied to it. A view calls it
     * before submitting, to keep the text and focus the field instead.
     *
     * @param field the field to ask about; not {@code null}
     * @return {@code true} if the field has a limit and its value is over it; {@code false} for a
     *         field without one
     */
    public static boolean exceeds(TextFieldBase<?, String> field) {
        LkTextLimit limit = ComponentUtil.getData(field, LkTextLimit.class);
        return limit != null && limit.length() > limit.max;
    }

    private void check() {
        int length = length();
        if (length > max) {
            flagged = true;
            field.setErrorMessage("At most " + max + " characters; this has " + length + ".");
            field.setInvalid(true);
        } else if (flagged) {
            flagged = false;
            field.setErrorMessage(null);
            // Hand the verdict back to the field's own constraints, e.g. "required" on emptied text.
            // A null context is what the field passes when it validates itself.
            field.setInvalid(field.getDefaultValidator().apply(field.getValue(), null).isError());
        }
    }

    private int length() {
        String value = field.getValue();
        return value == null ? 0 : value.length();
    }
}
