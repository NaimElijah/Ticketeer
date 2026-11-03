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
    private Span arrowSpan;

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
    public LkBtn full() { getStyle().set("width", "100%"); return this; }
    public LkBtn onClick(ComponentEventListener<ClickEvent<NativeButton>> l) { addClickListener(l); return this; }

    private void applyClasses() {
        addClassName(variant.cls());
        addClassName(size.cls());
    }
}
