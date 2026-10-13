package com.ticketing.system.ui.components.kit;

import com.vaadin.flow.component.html.Span;

/**
 * Coloured status dot + label — ports {@code .lk-statusdot}. Tones:
 * {@code ok / warn / muted / err}.
 */
public class LkStatusDot extends Span {

    public enum Tone {
        ok, warn, muted, err;
        public String cls() { return "lk-dot lk-dot-" + name(); }
    }

    public LkStatusDot(Tone tone, String label) {
        addClassName("lk-statusdot");
        Span dot = new Span();
        dot.addClassName("lk-dot");
        dot.addClassName("lk-dot-" + tone.name());
        Span lbl = new Span(label);
        add(dot, lbl);
    }
}
