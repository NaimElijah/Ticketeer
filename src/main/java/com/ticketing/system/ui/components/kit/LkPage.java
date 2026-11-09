package com.ticketing.system.ui.components.kit;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;

/**
 * Page wrapper — the {@code .lk-page} surface that every routed view
 * renders inside. Title row, optional subtitle, optional right-aligned
 * actions slot. Children added with {@link #add(Component...)} appear in
 * the body, stacked with the kit's vertical gap rhythm.
 */
public class LkPage extends Div {

    private final Div headRow = new Div();
    private final Div titleCol = new Div();
    private final H1 title = new H1();
    private final Div subtitle = new Div();
    private final Div actions = new Div();
    private boolean headInserted;

    /** A page with no heading row until a title, subtitle or actions are set. */
    public LkPage() {
        addClassName("lk-page");
        getStyle().set("margin", "0 auto");
        headRow.addClassName("lk-page-head");
        title.addClassName("lk-h1");
        subtitle.addClassName("lk-page-sub");
        actions.addClassName("lk-page-actions");
        titleCol.add(title);
        headRow.add(titleCol, actions);
    }

    /** A page whose {@code h1} is {@code titleText}. */
    public LkPage(String titleText) {
        this();
        title(titleText);
    }

    /** Sets the page's {@code h1}; {@code null} sets it empty. */
    public LkPage title(String t) {
        this.title.setText(t == null ? "" : t);
        ensureHead();
        return this;
    }

    /** Sets the line under the title; {@code null} or empty removes it. */
    public LkPage subtitle(String s) {
        subtitle.setText(s == null ? "" : s);
        if (s != null && !s.isEmpty()) {
            if (subtitle.getParent().isEmpty())
                titleCol.add(subtitle);
        } else if (subtitle.getParent().isPresent()) {
            titleCol.remove(subtitle);
        }
        ensureHead();
        return this;
    }

    /** Replaces the components at the right of the heading row, such as buttons. */
    public LkPage actions(Component... cs) {
        this.actions.removeAll();
        if (cs != null)
            this.actions.add(cs);
        ensureHead();
        return this;
    }

    /** Adds {@code cs} to the page body, below the heading row. */
    @Override
    public void add(Component... cs) {
        for (Component c : cs) {
            if (c == headRow)
                super.add(c);
            else
                super.add(c);
        }
    }

    private void ensureHead() {
        if (headInserted)
            return;
        getElement().insertChild(0, headRow.getElement());
        headInserted = true;
    }
}
