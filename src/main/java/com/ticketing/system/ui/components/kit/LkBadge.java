package com.ticketing.system.ui.components.kit;

import com.vaadin.flow.component.html.Span;

/**
 * Pill-shaped status badge. Ports {@code .lk-badge} from the kit.
 */
public class LkBadge extends Span {

    /** The badge's colour. {@code warn} and {@code warning} are two class names for the same amber. */
    public enum Tone {
        muted, success, warn, warning, error, primary, contrast;

        /** The theme class that gives the badge this colour. */
        public String cls() { return "lk-badge-" + name(); }
    }

    private Tone tone = Tone.muted;
    private boolean small;

    /** A {@link Tone#muted} badge showing {@code label}. */
    public LkBadge(String label) {
        setText(label);
        addClassName("lk-badge");
        addClassName(tone.cls());
    }

    /** A badge showing {@code label} in {@code tone}. */
    public LkBadge(String label, Tone tone) {
        this(label);
        tone(tone);
    }

    /** Replaces the badge's colour. */
    public LkBadge tone(Tone t) {
        for (Tone v : Tone.values()) removeClassName(v.cls());
        this.tone = t;
        addClassName(t.cls());
        return this;
    }

    /** Shows the badge in the small size; {@code small(true)}. */
    public LkBadge small() { return small(true); }

    /** Shows the badge in the small size, or the normal size when {@code s} is {@code false}. */
    public LkBadge small(boolean s) {
        if (s && !small) { addClassName("sm"); small = true; }
        else if (!s && small) { removeClassName("sm"); small = false; }
        return this;
    }
}
