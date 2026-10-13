package com.ticketing.system.ui.components;

import com.ticketing.system.ui.components.kit.LkIcon;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;

/**
 * Toast notifications wrapper — V2 req 3.5 implementation surface.
 *
 * <p>Centralises success / failure / warning toast styling so every presenter
 * reports action outcomes uniformly. Spec ownership: Mohamad (V2-CHECK-03).
 * Other lanes import this utility rather than calling
 * {@link Notification#show(String)} directly — that is an explicit
 * anti-pattern listed in {@code docs/vaadin-standards.html}.
 *
 * <p>Each toast carries an inline "×" close button so users can dismiss
 * immediately (without waiting for the auto-close). The kit's
 * {@code styles.css} pushes top-positioned notifications below the 56px
 * top bar so the card doesn't overlap with the bell / avatar.
 */
public final class Toasts {

    private Toasts() { }

    /** Success toast — green theme, 4s, top-end. */
    public static void success(String msg) {
        toast(msg, NotificationVariant.LUMO_SUCCESS, 4000);
    }

    /** Failure toast — red theme, 6s, top-end. Use for req 3.5 error messages. */
    public static void failure(String msg) {
        toast(msg, NotificationVariant.LUMO_ERROR, 6000);
    }

    /** Warning toast — amber theme, 5s, top-end. Use for near-expiry or "be careful" hints. */
    public static void warn(String msg) {
        toast(msg, NotificationVariant.LUMO_WARNING, 5000);
    }

    private static void toast(String msg, NotificationVariant variant, int durationMs) {
        Notification n = new Notification();
        n.setPosition(Notification.Position.TOP_END);
        n.setDuration(durationMs);
        n.addThemeVariants(variant);

        Div content = new Div();
        content.getStyle()
            .set("display", "flex")
            .set("align-items", "center")
            .set("gap", "14px");

        Span text = new Span(msg);
        text.getStyle().set("flex", "1 1 auto");
        content.add(text);

        Button close = new Button(new LkIcon("close", 14), e -> n.close());
        close.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE, ButtonVariant.LUMO_SMALL);
        close.getElement().setAttribute("aria-label", "Dismiss");
        close.getStyle()
            .set("color", "inherit")
            .set("opacity", "0.7")
            .set("margin", "-4px -6px -4px 0");
        content.add(close);

        n.add(content);
        n.open();
    }
}
