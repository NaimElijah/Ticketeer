package com.ticketing.system.ui.components.kit;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Span;

/**
 * Tiny shared factories — heading classes + text spans pre-decorated
 * with the kit's class names. Ports the trivial React helpers
 * ({@code H1, H2, H3, Txt}).
 */
public final class Lk {
    private static final String ROLE = "role";

    private Lk() { }

    public static H1 h1(String text) {
        H1 h = new H1(text);
        h.addClassName("lk-h1");
        return h;
    }

    public static H2 h2(String text) {
        H2 h = new H2(text);
        h.addClassName("lk-h2");
        return h;
    }

    public static H3 h3(String text) {
        H3 h = new H3(text);
        h.addClassName("lk-h3");
        return h;
    }

    public static Span txt(String text) {
        return new Span(text);
    }

    public static Span muted(String text) {
        Span s = new Span(text);
        s.getStyle().set("color", "var(--muted)");
        return s;
    }

    public static Span mono(String text) {
        Span s = new Span(text);
        s.addClassName("lk-mono");
        return s;
    }

    public static Span faint(String text) {
        Span s = new Span(text);
        s.getStyle().set("color", "var(--faint)");
        return s;
    }

    public static Div divider() {
        Div d = new Div();
        d.addClassName("bz-divider");
        return d;
    }

    /**
     * Marks {@code component} as an alert, so a screen reader announces it when it is inserted into the page.
     * For a message added after the page has loaded, such as an error a search or a filter choice brings
     * up; a message present from the start needs no role (#187).
     *
     * @param component the component that carries the message
     * @return {@code component}, for chaining
     */
    public static <T extends Component> T asAlert(T component) {
        component.getElement().setAttribute(ROLE, "alert");
        return component;
    }

    public static String withReason(String base, String reason) {
        return (reason != null && !reason.isBlank()) ? base + ": " + reason : base + ".";
    }
}
