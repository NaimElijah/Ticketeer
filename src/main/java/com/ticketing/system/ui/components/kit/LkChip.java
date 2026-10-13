package com.ticketing.system.ui.components.kit;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.Span;

/**
 * Pill-shaped chip used for category filters, filter triggers, and the
 * recent-search popover. Ports {@code .lk-chip}.
 */
public class LkChip extends Span {

    private boolean active;

    public LkChip(String label) {
        setText(label);
        addClassName("lk-chip");
    }

    public LkChip(Component icon, String label) {
        this(label);
        getElement().insertChild(0, icon.getElement());
    }

    public LkChip active() { return active(true); }
    public LkChip active(boolean a) {
        if (a && !active) { addClassName("on"); active = true; }
        else if (!a && active) { removeClassName("on"); active = false; }
        return this;
    }

    /** Add the trailing dropdown caret ("▾"). */
    public LkChip withCaret() {
        Span caret = new Span("▾");
        caret.addClassName("lk-chip-caret");
        add(caret);
        return this;
    }
}
