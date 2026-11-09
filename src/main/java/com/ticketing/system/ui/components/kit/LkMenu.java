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

    /** A menu of {@code children}, typically {@link Item}, {@link Label} and {@link Divider}; none leaves it empty. */
    public LkMenu(Component... children) {
        addClassName("lk-menu");
        if (children != null && children.length > 0) add(children);
    }

    /**
     * Convenience: append items and return self. The appended item has no click handler, so these
     * suit a menu that is only shown.
     */
    public LkMenu item(String label) { add(new Item(label)); return this; }

    /** Appends an item with an icon; see {@link #item(String)}. */
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

        /** An item with a text label and no icon. */
        public Item(String label) {
            addClassName("lk-menu-item");
            labelSlot.addClassName("lk-menu-lbl");
            labelSlot.setText(label);
            iconSlot.addClassName("lk-menu-ic");
            add(labelSlot);
        }

        /**
         * @param iconName the {@link LkIcon} glyph name for a 16 px icon before the label
         * @param label    the item's text
         */
        public Item(String iconName, String label) {
            this(label);
            icon(new LkIcon(iconName, 16));
        }

        /** Shows {@code icon} before the label, replacing an earlier one. */
        public Item icon(Component icon) {
            if (!hasIcon) {
                getElement().insertChild(0, iconSlot.getElement());
                hasIcon = true;
            }
            iconSlot.removeAll();
            iconSlot.add(icon);
            return this;
        }

        /** Shows {@code text} as a hint after the label, replacing an earlier one. */
        public Item hint(String text) {
            if (hintSlot == null) {
                hintSlot = new Span();
                hintSlot.addClassName("lk-menu-hint");
                add(hintSlot);
            }
            hintSlot.setText(text);
            return this;
        }

        /** Styles the item as a destructive action, such as "Sign Out". */
        public Item danger() { addClassName("danger"); return this; }

        /**
         * Highlights the item and adds a check mark to it. Call it once per item; each call adds
         * another check mark. For a single choice use {@link #choice(boolean)}, which also tells
         * screen readers.
         */
        public Item active() {
            addClassName("on");
            add(new LkIcon("check", 15));
            return this;
        }

        /**
         * Makes this item one option of a single choice, such as a select's: a radio that
         * tells a screen reader whether it is the chosen one, rather than leaving that to
         * colour and the check mark (#188; WCAG 1.3.1, 4.1.2). The chosen one is also
         * marked {@link #active()}.
         *
         * @param chosen whether this option is the current choice
         * @return this item, for chaining
         */
        public Item choice(boolean chosen) {
            getElement().setAttribute(Lk.ROLE, Lk.ROLE_RADIO);
            getElement().setAttribute(Lk.ARIA_CHECKED, String.valueOf(chosen));
            return chosen ? active() : this;
        }

        /** Runs {@code r} when the item is clicked; each call adds another listener. */
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
