package com.ticketing.system.ui.components.kit;

import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;

/**
 * KPI stat card — label · big value · optional delta with tone.
 * Ports the React {@code Stat} component.
 */
public class LkStat extends Div {

    public enum Tone {
        up, warn, none;
        public String cls() { return this == none ? "" : "lk-stat-delta " + name(); }
    }

    private final Span labelSpan = new Span();
    private final Div valueDiv = new Div();
    private Div deltaDiv;

    public LkStat(String label, String value) {
        addClassName("lk-stat");
        labelSpan.addClassName("lk-stat-label");
        valueDiv.addClassName("lk-stat-value");
        labelSpan.setText(label);
        valueDiv.setText(value);
        add(labelSpan, valueDiv);
    }

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
