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

    public enum Tone {
        warn, info, error;

        public String cls() {
            return "lk-banner-" + name();
        }
    }

    private final Span iconSlot = new Span();
    private final Div bodySlot = new Div();
    private final Div actionSlot = new Div();
    private Tone tone = Tone.warn;

    public LkBanner() {
        addClassName("lk-banner");
        addClassName(tone.cls());
        iconSlot.addClassName("lk-banner-icon");
        bodySlot.getStyle().set("flex", "1").set("min-width", "0");
        add(iconSlot, bodySlot, actionSlot);
    }

    public LkBanner(Tone tone, Component icon, String message) {
        this();
        tone(tone);
        if (icon != null)
            setIcon(icon);
        bodySlot.setText(message);
    }

    public LkBanner tone(Tone t) {
        for (Tone v : Tone.values())
            removeClassName(v.cls());
        this.tone = t;
        addClassName(t.cls());
        return this;
    }

    public LkBanner setIcon(Component icon) {
        iconSlot.removeAll();
        if (icon != null)
            iconSlot.add(icon);
        return this;
    }

    public LkBanner setBody(Component... cs) {
        bodySlot.removeAll();
        if (cs != null)
            bodySlot.add(cs);
        return this;
    }

    public LkBanner setAction(Component c) {
        actionSlot.removeAll();
        if (c != null)
            actionSlot.add(c);
        return this;
    }
}
