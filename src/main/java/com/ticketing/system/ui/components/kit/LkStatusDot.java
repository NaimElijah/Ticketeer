package com.ticketing.system.ui.components.kit;

import com.vaadin.flow.component.html.Span;

/**
 * Coloured status dot + label — ports {@code .lk-statusdot}. Tones:
 * {@code ok / warn / muted / err}.
 */
public class LkStatusDot extends Span {

    /** The dot's colour: {@code ok} green, {@code warn} amber, {@code muted} grey, {@code err} red. */
    public enum Tone {
        ok, warn, muted, err;

        /** The theme classes that colour the dot for this tone. */
        public String cls() { return "lk-dot lk-dot-" + name(); }
    }

    /**
     * @param tone  the dot's colour
     * @param label the text beside the dot, which carries the meaning for a reader who cannot see the colour
     */
    public LkStatusDot(Tone tone, String label) {
        addClassName("lk-statusdot");
        Span dot = new Span();
        dot.addClassName("lk-dot");
        dot.addClassName("lk-dot-" + tone.name());
        Span lbl = new Span(label);
        add(dot, lbl);
    }
}
