package com.ticketing.system.ui.components.kit;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Footer;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Header;
import com.vaadin.flow.component.html.Section;

/**
 * Card surface — ports the React {@code Card} component (title, subtitle,
 * headerRight slot, padded body, optional footer, plus {@code accent} /
 * {@code danger} / {@code flush} flags).
 *
 * <p>The title is an {@code h2}, under the page's {@code h1}, so a screen-reader user
 * can jump to the card by heading (#188; WCAG 1.3.1, 2.4.10; {@code HeadingStructureTest}).
 */
public class LkCard extends Section {

    private final Header header = new Header();
    private final Div titleWrap = new Div();
    private final H2 titleText = new H2();
    private final Div subtitle = new Div();
    private final Div headerRight = new Div();
    private final Div body = new Div();
    private Footer footer;
    private boolean headerActive;

    public LkCard() {
        addClassName("lk-card");
        header.addClassName("lk-card-h");
        titleText.addClassName("lk-card-title");
        subtitle.addClassName("lk-card-sub");
        body.addClassName("lk-card-body");
        body.getStyle().set("padding", "20px");
        titleWrap.add(titleText);
        header.add(titleWrap, headerRight);
        add(body);
    }

    public LkCard(String titleText) {
        this();
        title(titleText);
    }

    public LkCard title(String t) {
        String text = t == null ? "" : t;
        titleText.setText(text);
        // An empty heading is still announced as a heading, so a card without a title has none.
        titleText.setVisible(!text.isEmpty());
        ensureHeader();
        return this;
    }

    /**
     * Moves focus to the card's title, so a screen reader announces it: for a card whose content was
     * just swapped for another item's, such as an inbox's open conversation (#188, WCAG 2.4.3). The
     * title becomes focusable from a script but is no Tab stop. Does nothing while the card has no title.
     */
    public void focusTitle() {
        if (!titleText.isVisible()) {
            return;
        }
        Lk.focusFromScript(titleText);
    }

    public LkCard subtitle(String s) {
        subtitle.setText(s == null ? "" : s);
        if (s != null && !s.isEmpty()) {
            if (subtitle.getParent().isEmpty())
                titleWrap.add(subtitle);
        } else if (subtitle.getParent().isPresent()) {
            titleWrap.remove(subtitle);
        }
        ensureHeader();
        return this;
    }

    public LkCard headerRight(Component c) {
        headerRight.removeAll();
        if (c != null)
            headerRight.add(c);
        ensureHeader();
        return this;
    }

    public LkCard pad(int px) {
        body.getStyle().set("padding", px + "px");
        return this;
    }

    public LkCard accent() {
        addClassName("lk-card-accent");
        return this;
    }

    public LkCard danger() {
        addClassName("lk-card-danger");
        return this;
    }

    public LkCard flush() {
        addClassName("lk-card-flush");
        return this;
    }

    public LkCard footer(Component... cs) {
        if (footer == null) {
            footer = new Footer();
            footer.addClassName("lk-card-f");
            add(footer);
        } else {
            footer.removeAll();
        }
        if (cs != null)
            footer.add(cs);
        return this;
    }

    /** Adds children to the card body (default {@code add} target). */
    @Override
    public void add(Component... components) {
        // Avoid recursing when our own constructor add(body)/add(footer) runs.
        Component[] arr = components;
        for (Component c : arr) {
            if (c == header || c == body || c == footer) {
                super.add(c);
            } else {
                body.add(c);
            }
        }
    }

    /**
     * Clears the card BODY (the default {@link #add} target) without dismantling the card's own
     * header / body / footer structure. The inherited {@link Section#removeAll()} removes the
     * internal {@code body} element from the card, so anything added afterwards lands in a detached
     * body and never renders — which is why a detail panel re-rendered in place (e.g. the messaging
     * inboxes calling {@code removeAll()} then {@code add(...)}) showed only the header. Views that
     * truly want to drop the header should call {@code title("")} / {@code subtitle("")} instead.
     */
    @Override
    public void removeAll() {
        body.removeAll();
    }

    private void ensureHeader() {
        if (headerActive)
            return;
        getElement().insertChild(0, header.getElement());
        headerActive = true;
    }
}
