package com.ticketing.system.ui.components.venue;

import com.ticketing.system.Core.Application.dto.InventorySelectionDTO;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.NativeButton;
import com.vaadin.flow.component.html.Span;

/**
 * V2-RES-02 — quantity selector for a StandingZone.
 *
 * <p>Renders − / count / + buttons capped at {@code availableCount}.
 * A live price preview is kept in sync with every change:
 * "N tickets at $X.YZ each = $A.BC".
 *
 * <p>Minimum selectable quantity is 1; the component starts there
 * automatically when any tickets are available. When {@code availableCount}
 * is 0 the count is locked at 0 and the preview reads "Sold out".
 *
 * <p>Call {@link #getSelection()} to obtain a ready-made
 * {@link InventorySelectionDTO} for the reservation service.
 */
public class VkQuantitySelector extends Div {

    private int quantity;
    private final int maxQuantity;
    private final int priceCentsEach;
    private final Runnable onSelectionChange;

    private final NativeButton minusBtn;
    private final NativeButton plusBtn;
    private final Span countDisplay;
    private final Span pricePreview;

    public VkQuantitySelector(int availableCount, int priceCentsEach, Runnable onSelectionChange) {
        this.maxQuantity = availableCount;
        this.priceCentsEach = priceCentsEach;
        this.onSelectionChange = onSelectionChange;
        this.quantity = (availableCount > 0) ? 1 : 0;

        addClassName("vk-qty-selector");

        minusBtn = new NativeButton("−");
        minusBtn.addClassName("vk-qty-btn");
        minusBtn.addClickListener(e -> decrement());

        countDisplay = new Span();
        countDisplay.addClassName("vk-qty-count");

        plusBtn = new NativeButton("+");
        plusBtn.addClassName("vk-qty-btn");
        plusBtn.addClickListener(e -> increment());

        Div stepper = new Div();
        stepper.addClassName("vk-qty-stepper");
        stepper.add(minusBtn, countDisplay, plusBtn);

        pricePreview = new Span();
        pricePreview.addClassName("vk-qty-price");

        add(stepper, pricePreview);
        refresh();
    }

    // ---------- public API ----------

    /**
     * Returns a standing {@link InventorySelectionDTO} for the current
     * quantity, or {@code null} when no tickets are available (sold out).
     */
    public InventorySelectionDTO getSelection() {
        return (quantity > 0) ? InventorySelectionDTO.standing(quantity) : null;
    }

    public boolean hasSelection() {
        return quantity > 0;
    }

    public int getQuantity() {
        return quantity;
    }

    // ---------- internals ----------

    /** Package-private — increments the counter by one for tests. No-op at max. */
    void increment() {
        if (quantity < maxQuantity) {
            quantity++;
            refresh();
            notifyChange();
        }
    }

    /** Package-private — decrements the counter by one for tests. No-op at 1. */
    void decrement() {
        if (quantity > 1) {
            quantity--;
            refresh();
            notifyChange();
        }
    }

    private void refresh() {
        countDisplay.setText(String.valueOf(quantity));
        minusBtn.setEnabled(quantity > 1);
        plusBtn.setEnabled(quantity < maxQuantity);
        if (maxQuantity == 0) {
            pricePreview.setText("Sold out");
        } else {
            int totalCents = quantity * priceCentsEach;
            pricePreview.setText(quantity + " ticket" + (quantity == 1 ? "" : "s")
                + " at " + formatPrice(priceCentsEach) + " each = " + formatPrice(totalCents));
        }
    }

    private void notifyChange() {
        if (onSelectionChange != null) onSelectionChange.run();
    }

    private static String formatPrice(int cents) {
        return "$" + (cents / 100) + "." + String.format("%02d", cents % 100);
    }
}
