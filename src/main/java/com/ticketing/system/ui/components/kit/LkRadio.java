package com.ticketing.system.ui.components.kit;

import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.NativeLabel;
import com.vaadin.flow.component.html.Span;

import java.util.Objects;

/**
 * Labeled radio group — ports the React {@code Radio}. Visual only;
 * the {@code on} state is set per-option by matching {@code selected}
 * to the option text.
 */
public class LkRadio extends NativeLabel {

    private final Span labelSpan = new Span();
    private final Div group = new Div();
    private boolean hasLabel;

    /** An empty group; give it a label with {@link #label} and its options with {@link #options}. */
    public LkRadio() {
        addClassName("lk-field");
        labelSpan.addClassName("lk-label");
        group.addClassName("lk-radio-group");
        add(group);
    }

    /** Sets the label shown above the options. */
    public LkRadio label(String t) {
        labelSpan.setText(t);
        if (!hasLabel) {
            getElement().insertChild(0, labelSpan.getElement());
            hasLabel = true;
        }
        return this;
    }

    /**
     * Replaces the options.
     *
     * @param selected the text of the option drawn as chosen; {@code null} or text matching none
     *                 draws none as chosen
     * @param options  the options' texts, in order
     */
    public LkRadio options(String selected, String... options) {
        group.removeAll();
        for (String opt : options) {
            boolean on = Objects.equals(opt, selected);
            Span row = new Span();
            row.addClassName("lk-radio");
            if (on) row.addClassName("on");
            Span dot = new Span();
            dot.addClassName("lk-radio-dot");
            Span lbl = new Span(opt);
            row.add(dot, lbl);
            group.add(row);
        }
        return this;
    }
}
