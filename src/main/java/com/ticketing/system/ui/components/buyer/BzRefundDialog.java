package com.ticketing.system.ui.components.buyer;

import java.util.function.Consumer;

import com.ticketing.system.ui.components.kit.LkBanner;
import com.ticketing.system.ui.components.kit.LkConfirm;
import com.ticketing.system.ui.components.kit.LkIcon;
import com.vaadin.flow.component.textfield.TextArea;

/**
 * Member refund-request confirm (V2-DLG-01 / #284). A thin reusable wrapper over {@link LkConfirm}
 * that adds a reason {@link TextArea} and, on confirm, hands the reason back to the caller — which
 * runs the actual refund through {@code RefundPresenter}. Shared by {@code ReceiptView} and
 * {@code MyAccountView} so the confirm UX lives in one place.
 */
public final class BzRefundDialog {

    private BzRefundDialog() { }

    /**
     * Opens the refund confirm for one order. {@code onConfirm} is invoked (with the typed reason)
     * only if the member confirms; it is never called on cancel/dismiss.
     */
    public static void open(String orderLabel, double amount, Consumer<String> onConfirm) {
        TextArea reason = new TextArea("Reason for refund");
        reason.setPlaceholder("Optional — for your records.");
        reason.setMinHeight("80px");
        reason.setWidthFull();

        new LkConfirm("Request a refund",
                "Refund order " + orderLabel + " (" + money(amount) + ")? "
                    + "Approved refunds are processed in 3–5 business days.",
                LkConfirm.Severity.warn)
            .confirmText("Request refund")
            .addToBody(reason,
                new LkBanner(LkBanner.Tone.info, new LkIcon("info", 17),
                    "Refunds are returned to your original payment method."))
            .prompt()
            .thenAccept(ok -> {
                if (Boolean.TRUE.equals(ok) && onConfirm != null) {
                    onConfirm.accept(reason.getValue());
                }
            });
    }

    private static String money(double amount) {
        return String.format("$%,.2f", amount);
    }
}
