package com.ticketing.system.ui.components.kit;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;

/**
 * Warn / info banner — coloured strip with leading icon, message body,
 * and an optional trailing action slot. Ports the React {@code Banner}
 * component.
 */
public class LkBanner extends Div {

    /** The banner's colour and meaning. */
    public enum Tone {
        warn, info, error;

        /** The theme class that gives the banner this tone. */
        public String cls() {
            return "lk-banner-" + name();
        }
    }

    private final Span iconSlot = new Span();
    private final Div bodySlot = new Div();
    private final Div actionSlot = new Div();
    private Tone tone = Tone.warn;

    /** An empty {@link Tone#warn} banner; fill it with {@link #setIcon}, {@link #setBody} and {@link #setAction}. */
    public LkBanner() {
        addClassName("lk-banner");
        addClassName(tone.cls());
        iconSlot.addClassName("lk-banner-icon");
        bodySlot.getStyle().set("flex", "1").set("min-width", "0");
        add(iconSlot, bodySlot, actionSlot);
    }

    /**
     * @param tone    the banner's tone
     * @param icon    the leading icon; {@code null} for none
     * @param message the banner's text
     */
    public LkBanner(Tone tone, Component icon, String message) {
        this();
        tone(tone);
        if (icon != null)
            setIcon(icon);
        bodySlot.setText(message);
    }

    /** Replaces the banner's tone. */
    public LkBanner tone(Tone t) {
        for (Tone v : Tone.values())
            removeClassName(v.cls());
        this.tone = t;
        addClassName(t.cls());
        return this;
    }

    /** Replaces the leading icon; {@code null} removes it. */
    public LkBanner setIcon(Component icon) {
        iconSlot.removeAll();
        if (icon != null)
            iconSlot.add(icon);
        return this;
    }

    /** Replaces the message with {@code cs}, for a message that is more than text; {@code null} empties it. */
    public LkBanner setBody(Component... cs) {
        bodySlot.removeAll();
        if (cs != null)
            bodySlot.add(cs);
        return this;
    }

    /** Replaces the trailing action, such as a button; {@code null} removes it. */
    public LkBanner setAction(Component c) {
        actionSlot.removeAll();
        if (c != null)
            actionSlot.add(c);
        return this;
    }
}
