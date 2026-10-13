package com.ticketing.system.ui.components.kit;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;

import java.util.concurrent.CompletableFuture;

/**
 * Reusable confirm-before-acting dialog primitive (V2-DLG-01). Extends Vaadin
 * {@link Dialog} and resolves a {@link CompletableFuture}{@code <Boolean>} so a
 * caller wires a destructive action declaratively:
 *
 * <pre>{@code
 * new LkConfirm("Revoke manager", "Remove Carol from the team?", Severity.danger)
 *     .confirmText("Revoke")
 *     .prompt()
 *     .thenAccept(ok -> { if (Boolean.TRUE.equals(ok)) revoke(); });
 * }</pre>
 *
 * <p>The future completes {@code true} on confirm and {@code false} on cancel or
 * dismissal (ESC / click-outside) — so the {@code thenAccept} branch always runs
 * exactly once. Visuals follow the kit's {@code auth-card} language with a
 * severity-tinted top border ({@code info}=blue, {@code warn}=amber,
 * {@code danger}=red) and a matching leading icon; {@code danger} also reddens the
 * confirm button.
 *
 * <p>The dialog is built in the constructor (no UI context needed); only
 * {@link #prompt()} requires a live UI, so it can be constructed in tests. The body
 * accepts plain text plus any extra components ({@link LkBanner}, inline icons)
 * via {@link #addToBody(Component...)}. ({@code prompt()} rather than {@code open()}
 * because Vaadin's {@link Dialog#open()} returns {@code void} and cannot be
 * overridden with a future-returning method.)
 */
public class LkConfirm extends Dialog {

    public enum Severity {
        info("var(--primary)", "info"),
        warn("var(--warn)", "warning"),
        danger("var(--err)", "warning");

        private final String accent;
        private final String icon;

        Severity(String accent, String icon) {
            this.accent = accent;
            this.icon = icon;
        }
    }

    private final CompletableFuture<Boolean> result = new CompletableFuture<>();
    private final Div card = new Div();
    private final Span iconSlot = new Span();
    private final Span titleText = new Span();
    private final Div bodySlot = new Div();
    private final Button cancel = new Button("Cancel");
    private final Button confirm = new Button("Confirm");

    public LkConfirm(String title, String body) {
        this(title, body, Severity.warn);
    }

    public LkConfirm(String title, String body, Severity severity) {
        setWidth("min(440px, 100vw - 32px)");
        setMaxWidth("92vw");

        card.getStyle()
            .set("display", "flex")
            .set("flex-direction", "column")
            .set("gap", "12px")
            .set("padding-top", "4px");

        Div header = new Div(iconSlot, titleText);
        header.getStyle().set("display", "flex").set("align-items", "center").set("gap", "10px");
        titleText.getElement().setProperty("innerHTML", "<b>" + escape(title) + "</b>");
        titleText.getStyle().set("font-size", "1.05rem").set("color", "var(--ink, #0f172a)");

        bodySlot.getStyle().set("color", "var(--muted)").set("line-height", "1.5");
        if (body != null && !body.isEmpty()) {
            Span text = new Span();
            text.getElement().setProperty("innerHTML", escape(body).replace("\n", "<br>"));
            bodySlot.add(text);
        }

        card.add(header, bodySlot);
        add(card);

        cancel.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        cancel.addClickListener(e -> settle(false));
        confirm.addClickListener(e -> settle(true));
        getFooter().add(cancel, confirm);

        // ESC / click-outside dismissal resolves the future as "not confirmed".
        setCloseOnEsc(true);
        setCloseOnOutsideClick(true);
        addDialogCloseActionListener(e -> settle(false));

        severity(severity);
    }

    /** Sets the severity tint (top border + leading icon + confirm-button colour). */
    public LkConfirm severity(Severity severity) {
        card.getStyle().set("border-top", "3px solid " + severity.accent);

        iconSlot.removeAll();
        LkIcon icon = new LkIcon(severity.icon, 20);
        icon.getStyle().set("color", severity.accent).set("flex", "none");
        iconSlot.add(icon);

        confirm.removeThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_ERROR);
        if (severity == Severity.danger) {
            confirm.addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_ERROR);
        } else {
            confirm.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        }
        return this;
    }

    public LkConfirm confirmText(String text) {
        confirm.setText(text);
        return this;
    }

    public LkConfirm cancelText(String text) {
        cancel.setText(text);
        return this;
    }

    /** Append extra components (e.g. an {@link LkBanner} or inline icons) to the body. */
    public LkConfirm addToBody(Component... components) {
        if (components != null) bodySlot.add(components);
        return this;
    }

    /** Opens the dialog and returns the future that resolves on confirm/cancel. */
    public CompletableFuture<Boolean> prompt() {
        super.open();
        return result;
    }

    /**
     * One-line convenience: build, open, and return the result future.
     * {@code LkConfirm.confirm("Title", "Body", Severity.danger, "Delete", "Cancel").thenAccept(...)}.
     */
    public static CompletableFuture<Boolean> confirm(String title, String body, Severity severity,
                                                     String confirmLabel, String cancelLabel) {
        return new LkConfirm(title, body, severity)
            .confirmText(confirmLabel)
            .cancelText(cancelLabel)
            .prompt();
    }

    /** Completes the result future exactly once and closes the dialog. */
    void settle(boolean confirmed) {
        if (result.isDone()) return;
        result.complete(confirmed);
        close();
    }

    /** Test seam: the pending result future. */
    CompletableFuture<Boolean> result() {
        return result;
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
