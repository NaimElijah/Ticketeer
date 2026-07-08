package com.ticketing.system.ui.components.kit;

import com.vaadin.flow.component.html.Span;

/**
 * Pill-shaped status badge. Ports {@code .lk-badge} from the kit.
 */
public class LkBadge extends Span {

    public enum Tone {
        muted, success, warn, warning, error, primary, contrast;
        public String cls() { return "lk-badge-" + name(); }
    }

    private Tone tone = Tone.muted;
    private boolean small;

    public LkBadge(String label) {
        setText(label);
        addClassName("lk-badge");
        addClassName(tone.cls());
    }

    public LkBadge(String label, Tone tone) {
        this(label);
        tone(tone);
    }

    public LkBadge tone(Tone t) {
        for (Tone v : Tone.values()) removeClassName(v.cls());
        this.tone = t;
        addClassName(t.cls());
        return this;
    }

    public LkBadge small() { return small(true); }
    public LkBadge small(boolean s) {
        if (s && !small) { addClassName("sm"); small = true; }
        else if (!s && small) { removeClassName("sm"); small = false; }
        return this;
    }
}
