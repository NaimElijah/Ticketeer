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

    /**
     * The theme class that hides an element from sight but leaves it to screen readers
     * and the keyboard, such as a native control the kit draws itself (#188).
     */
    public static final String VISUALLY_HIDDEN = "lk-visually-hidden";

    // The ARIA vocabulary the kit's components share (#188).
    static final String ROLE = "role";
    static final String ARIA_LABEL = "aria-label";
    static final String ARIA_CHECKED = "aria-checked";
    static final String ARIA_HIDDEN = "aria-hidden";
    static final String ROLE_RADIO = "radio";
    static final String ROLE_CHECKBOX = "checkbox";
    static final String ARIA_CURRENT = "aria-current";
    static final String CURRENT_PAGE = "page";

    private static final String NAME_SEPARATOR = ": ";

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

    /**
     * Marks {@code component} as a status, a polite live region: a screen reader announces each change of its
     * text when the user is idle, without moving focus. For text a page rewrites in place, such as a result
     * count or a selection summary (#188, WCAG 4.1.3).
     *
     * <p>Mark the component once, before its first text, and change only its text afterwards: a region
     * inserted together with its text, or replaced, is not announced.
     *
     * @param component the component whose text changes
     * @return {@code component}, for chaining
     */
    public static <T extends Component> T asStatus(T component) {
        component.getElement().setAttribute(ROLE, "status");
        return component;
    }

    /**
     * Marks {@code component} as a named group, so a screen reader says what a set of
     * related controls, such as a list of checkboxes, decides when focus enters it (#188).
     *
     * @param component the container that holds the controls
     * @param name      the group's accessible name
     * @return {@code component}, for chaining
     */
    public static <T extends Component> T asGroup(T component, String name) {
        component.getElement().setAttribute(ROLE, "group");
        component.getElement().setAttribute(ARIA_LABEL, name);
        return component;
    }

    public static String withReason(String base, String reason) {
        return (reason != null && !reason.isBlank()) ? base + ": " + reason : base + ".";
    }

    /**
     * The accessible name of a control that shows its value but not its label, such as a
     * popover field's trigger: "Country: Israel", or the value alone while it has no label (#188).
     */
    static String labelAndValue(String label, String value) {
        String shown = value == null ? "" : value;
        return label == null || label.isBlank() ? shown : label + NAME_SEPARATOR + shown;
    }
}
