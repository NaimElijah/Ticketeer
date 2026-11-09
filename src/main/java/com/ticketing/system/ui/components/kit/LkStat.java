package com.ticketing.system.ui.components.kit;

import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;

/**
 * KPI stat card — label · big value · optional delta with tone.
 * Ports the React {@code Stat} component.
 */
public class LkStat extends Div {

    /** A delta's colour: {@code up} green, {@code warn} amber, {@code none} the muted default. */
    public enum Tone {
        up, warn, none;

        /** The theme classes for this tone; empty for {@code none}. */
        public String cls() { return this == none ? "" : "lk-stat-delta " + name(); }
    }

    private final Span labelSpan = new Span();
    private final Div valueDiv = new Div();
    private Div deltaDiv;

    /**
     * @param label the caption
     * @param value the figure, already formatted
     */
    public LkStat(String label, String value) {
        addClassName("lk-stat");
        labelSpan.addClassName("lk-stat-label");
        valueDiv.addClassName("lk-stat-value");
        labelSpan.setText(label);
        valueDiv.setText(value);
        add(labelSpan, valueDiv);
    }

    /**
     * Shows a line under the value, replacing an earlier one.
     *
     * @param text the line, such as a change since last week
     * @param tone its tone; {@code null} reads as {@link Tone#none}
     */
    public LkStat delta(String text, Tone tone) {
        if (deltaDiv == null) {
            deltaDiv = new Div();
            add(deltaDiv);
        }
        deltaDiv.setText(text);
        deltaDiv.setClassName("lk-stat-delta");
        if (tone != null && tone != Tone.none) deltaDiv.addClassName(tone.name());
        return this;
    }
}
