package com.ticketing.system.ui.components.kit;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.Focusable;
import com.vaadin.flow.component.HasValue;
import com.vaadin.flow.component.shared.HasValidationProperties;
import com.vaadin.flow.data.binder.HasValidator;
import com.vaadin.flow.data.binder.ValidationResult;

/**
 * The fields a form refused on its last submit (#188, WCAG 3.3.1, 3.3.2). Each is marked invalid with a
 * message saying what is wrong, and focus moves to the first, so a refusal is no longer carried only by a
 * toast that vanishes and is tied to no field.
 *
 * <p>A view calls {@link #clear()} as a submit starts, {@link #reject} for each field a check refuses, in
 * the order the form shows them, and then {@link #focusFirst()}. Clearing on the next submit undoes a mark
 * that a rule across fields set, such as a confirmation that no longer differs once the password changed,
 * and hands the field back to its own constraints: one still breaking them, such as an age above its
 * maximum, stays invalid, saying what they say. That message came from no run of the field's own validation,
 * so Vaadin counts it as one the view set and would keep it through the next edit; such a field is therefore
 * handed back again on its next edit, as below, though it no longer counts as refused.
 *
 * <p>A refusal also gives way once the user edits the field (#188, review C1). A Vaadin field runs its own
 * validation on every value change and sets {@code invalid} from its own constraints, but it never
 * replaces an error message set on it, so a field refused as empty and then given an out-of-range value
 * would go on saying the refusal. The first change after a refusal therefore hands the field back, as
 * {@link #clear()} does: the refusal goes, and the field is invalid only if its own constraints refuse the
 * new value, with the message they give (#189); from then on its own validation decides. This holds
 * for a field whose value is not its answer too, such as a search box with its own validation turned off
 * ({@code setManualValidation}): typing in it lifts the mark unless its own constraints fail, and its
 * view refuses it again on the next submit if it is still unanswered.
 *
 * <p>Not thread-safe; a view uses it from its UI thread.
 */
public final class LkFormErrors {

    /** The fields refused since {@link #clear()}, in the order refused. */
    private final List<Mark> refused = new ArrayList<>();
    /** The fields {@link #clear()} handed back still invalid, with the message it gave them. */
    private final List<Mark> handedBack = new ArrayList<>();
    /** The fields this instance already listens to, so a field refused on every submit gets one listener. */
    private final Set<HasValidationProperties> listening = new HashSet<>();
    private Runnable focusFirst;

    /**
     * Unmarks every field the last submit refused and hands it back to its own constraints; a submit calls
     * it before its checks. A field still breaking its own constraints stays invalid, saying what they say,
     * and its next edit hands it back again, as the class comment says. A field whose message has changed
     * since is left alone: the user has edited it, or another check, such as {@link LkTextLimit}, marked it.
     */
    public void clear() {
        List<HasValidationProperties> shown = marks()
            .filter(Mark::isShown)
            .map(Mark::field)
            .distinct()
            .toList();
        refused.clear();
        handedBack.clear();
        for (HasValidationProperties field : shown) {
            handBackToOwnRule(field);
            if (field.isInvalid()) {
                handedBack.add(new Mark(field, field.getErrorMessage()));
            }
        }
        focusFirst = null;
    }

    /**
     * Marks {@code field} invalid with {@code message}; the first field refused since {@link #clear()} is
     * the one {@link #focusFirst()} focuses. The user's next change to a field that holds a value
     * ({@link HasValue}) hands it back to its own constraints, as the class comment says.
     *
     * <p>Side effect: the first refusal of a field adds a value-change listener to it, kept for the
     * field's life.
     *
     * @param field   the refused field; not {@code null}
     * @param message what is wrong with it, shown under it and read with it; not blank
     */
    public <F extends Component & HasValidationProperties & Focusable<?>> void reject(F field, String message) {
        mark(field, message, field::focus);
        if (field instanceof HasValue<?, ?> valued && listening.add(field)) {
            valued.addValueChangeListener(event -> dropShownRefusal(field));
        }
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

    /**
     * {@code true} while no field has been refused since {@link #clear()}. A field {@link #clear()} left
     * invalid by its own constraints does not count: the submit's own checks decide whether to refuse it.
     */
    public boolean isEmpty() {
        return refused.isEmpty();
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
        refused.add(new Mark(field, message));
        if (focusFirst == null) {
            focusFirst = focus;
        }
    }

    // Hands back fully rather than only dropping the message: a field with manual validation would
    // otherwise stay invalid with nothing saying why.
    private void dropShownRefusal(HasValidationProperties field) {
        if (marks().anyMatch(mark -> mark.field() == field && mark.isShown())) {
            handBackToOwnRule(field);
        }
    }

    private Stream<Mark> marks() {
        return Stream.concat(refused.stream(), handedBack.stream());
    }

    // Invalid by the field's own constraints alone, saying what they say. On an edit, the field's own
    // validation ran before this on the same change and kept the refusal's message, which it treats as one the
    // view set, so dropping the message without putting the rule's in its place would leave it invalid and
    // blank (#189).
    private static void handBackToOwnRule(HasValidationProperties field) {
        ValidationResult verdict = ownVerdict(field);
        field.setErrorMessage(verdict.isError() ? verdict.getErrorMessage() : null);
        field.setInvalid(verdict.isError());
    }

    private static ValidationResult ownVerdict(HasValidationProperties field) {
        if (field instanceof HasValidator<?> validated && field instanceof HasValue<?, ?> valued) {
            return ownVerdict(validated, valued.getValue());
        }
        return ValidationResult.ok();
    }

    // A null context is what a Vaadin field passes when it validates itself.
    @SuppressWarnings("unchecked") // a field's default validator takes the value that same field holds
    private static <V> ValidationResult ownVerdict(HasValidator<V> field, Object value) {
        return field.getDefaultValidator().apply((V) value, null);
    }

    private record Mark(HasValidationProperties field, String message) {

        /** Whether the field still shows this mark's message, rather than one something else set since. */
        boolean isShown() {
            return message.equals(field.getErrorMessage());
        }
    }
}
