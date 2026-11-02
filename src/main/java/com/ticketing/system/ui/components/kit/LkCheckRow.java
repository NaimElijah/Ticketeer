package com.ticketing.system.ui.components.kit;

import com.vaadin.flow.component.html.NativeLabel;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.dom.Element;

import java.util.function.Consumer;

/**
 * Checkbox + label row — ports {@code .lk-checkrow}. Click-to-toggle is
 * wired by default; pass {@link #onToggle(Consumer)} for a callback.
 *
 * <p>The row is a {@code <label>} around a native {@code <input type="checkbox">},
 * so a keyboard reaches it with Tab and toggles it with Space, and a screen reader
 * names it by the label and reads its state (#188; WCAG 2.1.1, 4.1.2;
 * {@code CheckRowA11yTest}). The input is visually hidden: the kit's
 * {@code .lk-checkbox} box draws its state, and its focus ring.
 */
public class LkCheckRow extends NativeLabel {

    private static final String INPUT_TAG = "input";
    private static final String TYPE_CHECKBOX = "checkbox";
    private static final String CHECKED = "checked";
    private static final String CLICK = "click";

    private final Element input = new Element(INPUT_TAG);
    private final Span box = new Span();
    private boolean on;
    private Consumer<Boolean> onToggle;

    /**
     * @param label   the row's text, which also names the checkbox
     * @param checked the initial state
     */
    public LkCheckRow(String label, boolean checked) {
        addClassName("lk-checkrow");
        input.setAttribute("type", TYPE_CHECKBOX);
        input.getClassList().add(Lk.VISUALLY_HIDDEN);
        box.addClassName("lk-checkbox");
        // The box only repeats the state the input already exposes.
        box.getElement().setAttribute("aria-hidden", "true");
        getElement().appendChild(input);
        add(box, new Span(label));
        setChecked(checked);
        // The browser turns a click on the label and Space on the input into one click on the input,
        // after flipping it, so listening on the input alone toggles once per user action.
        input.addEventListener(CLICK, e -> toggle());
    }

    /**
     * Sets the state without calling the {@link #onToggle(Consumer)} callback, so a
     * callback may set another row without looping.
     *
     * @param checked the new state
     * @return this row, for chaining
     */
    public LkCheckRow setChecked(boolean checked) {
        this.on = checked;
        input.setProperty(CHECKED, checked);
        box.removeAll();
        if (checked) {
            box.addClassName("on");
            box.add(new LkIcon("check", 11, 2.6));
        } else {
            box.removeClassName("on");
        }
        return this;
    }

    /** @return {@code true} when the row is checked */
    public boolean isChecked() { return on; }

    /** Callback invoked after each toggle by the user with the new state. */
    public LkCheckRow onToggle(Consumer<Boolean> handler) {
        this.onToggle = handler;
        return this;
    }

    private void toggle() {
        setChecked(!on);
        if (onToggle != null) onToggle.accept(on);
    }
}
