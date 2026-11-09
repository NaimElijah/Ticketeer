package com.ticketing.system.ui.components.kit;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.ComponentEventListener;
import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.html.NativeButton;
import com.vaadin.flow.component.html.Span;

/**
 * Native {@code <button>} styled with the kit's {@code .lk-btn} classes: a
 * {@link Variant} for its colour and a {@link Size}, {@link Variant#secondary} and
 * {@link Size#m} until set.
 */
public class LkBtn extends NativeButton {

    /** The button's colour and emphasis. */
    public enum Variant {
        primary, secondary, tertiary, error, success;

        /** The theme class that gives the button this variant. */
        public String cls() { return "lk-btn-" + name(); }
    }

    /** The button's size: small, medium or large. */
    public enum Size {
        s, m, l;

        /** The theme class that gives the button this size. */
        public String cls() { return "lk-btn-" + name(); }
    }

    /** Theme class for a translucent white button on a coloured bar, such as the top bar's "Register". */
    public static final String GHOST_LIGHT = "lk-btn-ghost-light";

    private Variant variant = Variant.secondary;
    private Size size = Size.m;
    private final Span labelSpan = new Span();
    private Component iconNode;
    private Span arrowSpan;

    /** A button showing {@code label}. */
    public LkBtn(String label) {
        addClassName("lk-btn");
        applyClasses();
        labelSpan.setText(label);
        add(labelSpan);
    }

    /** Replaces the button's variant. */
    public LkBtn variant(Variant v) {
        for (Variant val : Variant.values()) removeClassName(val.cls());
        this.variant = v;
        addClassName(v.cls());
        return this;
    }

    /** Replaces the button's size. */
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

    /** Replaces the visible label. It does not change a name set by {@link #forRow}. */
    public LkBtn label(String text) { labelSpan.setText(text); return this; }

    /**
     * Shows, or hides, a "→" after the label. The arrow is decoration, hidden from screen readers, so the
     * button is named by its words alone (#188, WCAG 1.1.1).
     *
     * @param show whether the arrow shows
     * @return this button, for chaining
     */
    public LkBtn arrow(boolean show) {
        if (arrowSpan == null) {
            // The space keeps the text "Label →"; a flex item drops it at the start of its line, so the gap
            // between label and arrow stays the button's own.
            arrowSpan = Lk.decoration(" →");
            add(arrowSpan);
        }
        arrowSpan.setVisible(show);
        return this;
    }

    /**
     * Names the button for the row it acts on, "Remove: Coldplay" ({@link Lk#actionFor}); it still shows its
     * bare label. Call it after the label is final: a later {@link #label} does not rename it.
     *
     * @param row what names the row
     * @return this button, for chaining
     */
    public LkBtn forRow(String row) {
        getElement().setAttribute(Lk.ARIA_LABEL, Lk.actionFor(labelSpan.getText(), row));
        return this;
    }
    /** Stretches the button to the full width of its container. */
    public LkBtn full() { getStyle().set("width", "100%"); return this; }

    /** Adds a click listener; each call adds another. */
    public LkBtn onClick(ComponentEventListener<ClickEvent<NativeButton>> l) { addClickListener(l); return this; }

    private void applyClasses() {
        addClassName(variant.cls());
        addClassName(size.cls());
    }
}
