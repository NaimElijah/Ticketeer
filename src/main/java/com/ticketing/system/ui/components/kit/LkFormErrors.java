package com.ticketing.system.ui.components.kit;

import java.util.ArrayList;
import java.util.List;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.Focusable;
import com.vaadin.flow.component.shared.HasValidationProperties;

/**
 * The fields a form refused on its last submit (#188, WCAG 3.3.1, 3.3.2). Each is marked invalid with a
 * message saying what is wrong, and focus moves to the first, so a refusal is no longer carried only by a
 * toast that vanishes and is tied to no field.
 *
 * <p>A view calls {@link #clear()} as a submit starts, {@link #reject} for each field a check refuses, in
 * the order the form shows them, and then {@link #focusFirst()}. Clearing on the next submit undoes a mark
 * that a rule across fields set, such as a confirmation that no longer differs once the password changed.
 * A mark also goes once the user edits the field: a Vaadin field runs its own validation on every value
 * change, which sets {@code invalid} from the field's own constraints. A field whose value is not its
 * answer, such as a search box, turns that off ({@code setManualValidation}) and is cleared by its view.
 *
 * <p>Not thread-safe; a view uses it from its UI thread.
 */
public final class LkFormErrors {

    private final List<Mark> marked = new ArrayList<>();
    private Runnable focusFirst;

    /**
     * Unmarks every field the last submit refused; a submit calls it before its checks. A field whose
     * message has changed since is left alone: another check, such as {@link LkTextLimit}, marked it.
     */
    public void clear() {
        marked.stream()
            .filter(mark -> mark.message().equals(mark.field().getErrorMessage()))
            .forEach(mark -> mark.field().setInvalid(false));
        marked.clear();
        focusFirst = null;
    }

    /**
     * Marks {@code field} invalid with {@code message}; the first field refused since {@link #clear()} is
     * the one {@link #focusFirst()} focuses.
     *
     * @param field   the refused field; not {@code null}
     * @param message what is wrong with it, shown under it and read with it; not blank
     */
    public <F extends Component & HasValidationProperties & Focusable<?>> void reject(F field, String message) {
        mark(field, message, field::focus);
    }

    /**
     * Marks a repeated-rows field refused: its first row shows {@code message}, and focus goes to that row.
     *
     * @param rows    the refused field; not {@code null}
     * @param message what is wrong with it; not blank
     * @see #reject(Component, String)
     */
    public void reject(LkTextRows rows, String message) {
        mark(rows, message, rows::focus);
    }

    /** {@code true} while no field has been refused since {@link #clear()}. */
    public boolean isEmpty() {
        return marked.isEmpty();
    }

    /** Moves focus to the first field refused since {@link #clear()}; does nothing when none was. */
    public void focusFirst() {
        if (focusFirst != null) {
            focusFirst.run();
        }
    }

    private void mark(HasValidationProperties field, String message, Runnable focus) {
        field.setErrorMessage(message);
        field.setInvalid(true);
        marked.add(new Mark(field, message));
        if (focusFirst == null) {
            focusFirst = focus;
        }
    }

    private record Mark(HasValidationProperties field, String message) { }
}
