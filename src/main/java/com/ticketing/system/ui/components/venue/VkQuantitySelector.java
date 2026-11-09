package com.ticketing.system.ui.components.venue;

import com.ticketing.system.catalog.application.dto.InventorySelectionDTO;
import com.ticketing.system.ui.components.Money;
import com.ticketing.system.ui.components.kit.Lk;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.NativeButton;
import com.vaadin.flow.component.html.Span;

/**
 * V2-RES-02 — quantity selector for a StandingZone.
 *
 * <p>Renders − / count / + buttons capped at {@code availableCount}, named "Decrease quantity" and
 * "Increase quantity". At a limit a button stays focusable and says it is unavailable
 * ({@code aria-disabled}) instead of being disabled. A price preview, a polite live region, is
 * kept in sync with every change: "N tickets at $X.YZ each = $A.BC" (#188, WCAG 4.1.3).
 *
 * <p>Minimum selectable quantity is 1; the component starts there
 * automatically when any tickets are available. When {@code availableCount}
 * is 0 the count is locked at 0 and the preview reads "Sold out".
 *
 * <p>Call {@link #getSelection()} to obtain a ready-made
 * {@link InventorySelectionDTO} for the reservation service.
 */
public class VkQuantitySelector extends Div {

    private static final String ARIA_LABEL = "aria-label";
    private static final String DECREASE = "Decrease quantity";
    private static final String INCREASE = "Increase quantity";
    private static final String ARIA_DISABLED = "aria-disabled";

    private int quantity;
    private final int maxQuantity;
    private final int priceCentsEach;
    private final Runnable onSelectionChange;

    private final NativeButton minusBtn;
    private final NativeButton plusBtn;
    private final Span countDisplay;
    private final Span pricePreview;

    /**
     * @param availableCount    the most tickets that can be picked; 0 means sold out
     * @param priceCentsEach    one ticket's price, in cents, for the price preview
     * @param onSelectionChange runs after each change of the quantity the user makes; not run for the
     *                          initial quantity; may be {@code null}
     */
    public VkQuantitySelector(int availableCount, int priceCentsEach, Runnable onSelectionChange) {
        this.maxQuantity = availableCount;
        this.priceCentsEach = priceCentsEach;
        this.onSelectionChange = onSelectionChange;
        this.quantity = (availableCount > 0) ? 1 : 0;

        addClassName("vk-qty-selector");

        minusBtn = new NativeButton("−");
        minusBtn.addClassName("vk-qty-btn");
        // Named for what it does; the glyph alone reads as "minus" or not at all (#188).
        minusBtn.getElement().setAttribute(ARIA_LABEL, DECREASE);
        minusBtn.addClickListener(e -> decrement());

        countDisplay = new Span();
        countDisplay.addClassName("vk-qty-count");

        plusBtn = new NativeButton("+");
        plusBtn.addClassName("vk-qty-btn");
        plusBtn.getElement().setAttribute(ARIA_LABEL, INCREASE);
        plusBtn.addClickListener(e -> increment());

        Div stepper = new Div();
        stepper.addClassName("vk-qty-stepper");
        stepper.add(minusBtn, countDisplay, plusBtn);

        // A status before its first text, so each new count and price is announced (#188).
        pricePreview = Lk.asStatus(new Span());
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

    /** Whether a quantity is selected, which is always so unless the zone is sold out. */
    public boolean hasSelection() {
        return quantity > 0;
    }

    /** The quantity shown, 0 when the zone is sold out. */
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
        // aria-disabled, not disabled: a disabled button drops the focus the user stepped to the limit with.
        // increment and decrement refuse to pass the limit themselves.
        markUnavailable(minusBtn, quantity <= 1);
        markUnavailable(plusBtn, quantity >= maxQuantity);
        if (maxQuantity == 0) {
            pricePreview.setText("Sold out");
        } else {
            // In long: a large zone at a high price passes Integer.MAX_VALUE cents.
            long totalCents = (long) quantity * priceCentsEach;
            pricePreview.setText(quantity + " ticket" + (quantity == 1 ? "" : "s")
                + " at " + Money.format(priceCentsEach) + " each = " + Money.format(totalCents));
        }
    }

    private static void markUnavailable(NativeButton button, boolean unavailable) {
        button.getElement().setAttribute(ARIA_DISABLED, String.valueOf(unavailable));
    }

    private void notifyChange() {
        if (onSelectionChange != null) onSelectionChange.run();
    }
}
