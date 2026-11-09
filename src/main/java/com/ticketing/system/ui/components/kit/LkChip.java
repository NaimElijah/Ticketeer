package com.ticketing.system.ui.components.kit;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.Span;

/**
 * Pill-shaped chip used for category filters, filter triggers, and the
 * recent-search popover. Ports {@code .lk-chip}.
 */
public class LkChip extends Span {

    private boolean active;

    /** A chip showing {@code label}, not highlighted. */
    public LkChip(String label) {
        setText(label);
        addClassName("lk-chip");
    }

    /**
     * @param icon  shown before the label; not {@code null}
     * @param label the chip's text
     */
    public LkChip(Component icon, String label) {
        this(label);
        getElement().insertChild(0, icon.getElement());
    }

    /** Highlights the chip as chosen; {@code active(true)}. */
    public LkChip active() { return active(true); }

    /** Highlights the chip as chosen, or removes the highlight when {@code a} is {@code false}. */
    public LkChip active(boolean a) {
        if (a && !active) { addClassName("on"); active = true; }
        else if (!a && active) { removeClassName("on"); active = false; }
        return this;
    }

    /** Add the trailing dropdown caret ("▾"). */
    public LkChip withCaret() {
        Span caret = Lk.decoration("▾");
        caret.addClassName("lk-chip-caret");
        add(caret);
        return this;
    }
}
