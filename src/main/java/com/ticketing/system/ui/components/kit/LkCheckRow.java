package com.ticketing.system.ui.components.kit;

import com.vaadin.flow.component.html.Span;

import java.util.function.Consumer;

/**
 * Checkbox + label row — ports {@code .lk-checkrow}. Click-to-toggle is
 * wired by default; pass {@link #onToggle(Consumer)} for a callback. Use
 * {@link #wrapperClass(String)} to swap the default {@code .lk-checkrow}
 * wrapper for a different theme class (e.g. {@code auth-remember}).
 */
public class LkCheckRow extends Span {

    private final Span box = new Span();
    private boolean on;
    private Consumer<Boolean> onToggle;

    public LkCheckRow(String label, boolean checked) {
        addClassName("lk-checkrow");
        box.addClassName("lk-checkbox");
        Span lbl = new Span(label);
        add(box, lbl);
        setChecked(checked);
        getElement().addEventListener("click", e -> toggle());
    }

    public LkCheckRow setChecked(boolean checked) {
        this.on = checked;
        box.removeAll();
        if (checked) {
            box.addClassName("on");
            box.add(new LkIcon("check", 11, 2.6));
        } else {
            box.removeClassName("on");
        }
        return this;
    }

    public boolean isChecked() { return on; }

    /** Callback invoked after each toggle with the new state. */
    public LkCheckRow onToggle(Consumer<Boolean> handler) {
        this.onToggle = handler;
        return this;
    }

    /**
     * Override the default {@code .lk-checkrow} wrapper class — used by
     * the auth screens to apply {@code .auth-remember} instead, which
     * shares the {@code .lk-checkbox} visual but has its own font / gap.
     */
    public LkCheckRow wrapperClass(String className) {
        removeClassName("lk-checkrow");
        addClassName(className);
        return this;
    }

    private void toggle() {
        setChecked(!on);
        if (onToggle != null) onToggle.accept(on);
    }
}
