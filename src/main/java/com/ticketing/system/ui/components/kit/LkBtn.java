package com.ticketing.system.ui.components.kit;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.ComponentEventListener;
import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.html.NativeButton;
import com.vaadin.flow.component.html.Span;

/**
 * Native {@code <button>} styled with the kit's {@code .lk-btn} classes.
 * Ports the React {@code Btn} component — variants and sizes match
 * {@code lumo-kit.jsx} verbatim.
 */
public class LkBtn extends NativeButton {

    public enum Variant {
        primary, secondary, tertiary, error, success;
        public String cls() { return "lk-btn-" + name(); }
    }
    public enum Size {
        s, m, l;
        public String cls() { return "lk-btn-" + name(); }
    }

    public static final String GHOST_LIGHT = "lk-btn-ghost-light";

    private Variant variant = Variant.secondary;
    private Size size = Size.m;
    private final Span labelSpan = new Span();
    private Component iconNode;

    public LkBtn(String label) {
        addClassName("lk-btn");
        applyClasses();
        labelSpan.setText(label);
        add(labelSpan);
    }

    public LkBtn variant(Variant v) {
        for (Variant val : Variant.values()) removeClassName(val.cls());
        this.variant = v;
        addClassName(v.cls());
        return this;
    }

    public LkBtn size(Size s) {
        for (Size val : Size.values()) removeClassName(val.cls());
        this.size = s;
        addClassName(s.cls());
        return this;
    }

    /** Insert (or replace) an icon before the label. Pass {@code null} to clear. */
    public LkBtn icon(Component icon) {
        if (iconNode != null) remove(iconNode);
        iconNode = icon;
        if (icon != null) {
            icon.getElement().getClassList().add("lk-btn-icon");
            getElement().insertChild(0, icon.getElement());
        }
        return this;
    }

    public LkBtn label(String text) { labelSpan.setText(text); return this; }
    public LkBtn full() { getStyle().set("width", "100%"); return this; }
    public LkBtn onClick(ComponentEventListener<ClickEvent<NativeButton>> l) { addClickListener(l); return this; }

    private void applyClasses() {
        addClassName(variant.cls());
        addClassName(size.cls());
    }
}
