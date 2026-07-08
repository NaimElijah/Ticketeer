package com.ticketing.system.ui.components.kit;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.NativeButton;
import com.vaadin.flow.component.html.Span;

/**
 * Popover menu primitives — flex column of items styled via the kit's
 * {@code .lk-menu*} classes. Nested {@link Item}, {@link Label}, and
 * {@link Divider} match the React {@code MenuItem / MenuLabel /
 * MenuDivider} components.
 */
public class LkMenu extends Div {

    public LkMenu(Component... children) {
        addClassName("lk-menu");
        if (children != null && children.length > 0) add(children);
    }

    /** Convenience: append items and return self. */
    public LkMenu item(String label) { add(new Item(label)); return this; }
    public LkMenu item(String iconName, String label) { add(new Item(iconName, label)); return this; }
    public LkMenu label(String text) { add(new Label(text)); return this; }
    public LkMenu divider() { add(new Divider()); return this; }

    // ---------------- nested ----------------

    /**
     * Single clickable menu item. Click handler is attached via
     * {@link #onClick(Runnable)} (button click).
     */
    public static class Item extends NativeButton {
        private final Span iconSlot = new Span();
        private final Span labelSlot = new Span();
        private Span hintSlot;
        private boolean hasIcon;

        public Item(String label) {
            addClassName("lk-menu-item");
            labelSlot.addClassName("lk-menu-lbl");
            labelSlot.setText(label);
            iconSlot.addClassName("lk-menu-ic");
            add(labelSlot);
        }

        public Item(String iconName, String label) {
            this(label);
            icon(new LkIcon(iconName, 16));
        }

        public Item icon(Component icon) {
            if (!hasIcon) {
                getElement().insertChild(0, iconSlot.getElement());
                hasIcon = true;
            }
            iconSlot.removeAll();
            iconSlot.add(icon);
            return this;
        }

        public Item hint(String text) {
            if (hintSlot == null) {
                hintSlot = new Span();
                hintSlot.addClassName("lk-menu-hint");
                add(hintSlot);
            }
            hintSlot.setText(text);
            return this;
        }

        public Item danger() { addClassName("danger"); return this; }

        public Item active() {
            addClassName("on");
            add(new LkIcon("check", 15));
            return this;
        }

        public Item onClick(Runnable r) {
            addClickListener(e -> r.run());
            return this;
        }
    }

    /** Tiny uppercase section label. */
    public static class Label extends Div {
        public Label(String text) {
            addClassName("lk-menu-label");
            Span lbl = new Span(text);
            add(lbl);
        }

        /** Add a small right-aligned action link (e.g. "Clear"). */
        public Label action(String label, Runnable r) {
            NativeButton btn = new NativeButton(label);
            btn.addClassName("lk-link-btn");
            btn.addClickListener(e -> r.run());
            add(btn);
            return this;
        }
    }

    /** 1px horizontal rule. */
    public static class Divider extends Div {
        public Divider() {
            addClassName("lk-menu-div");
        }
    }
}
