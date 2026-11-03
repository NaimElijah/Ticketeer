package com.ticketing.system.ui.views.order;

import com.ticketing.system.ui.components.Money;
import com.ticketing.system.ui.components.Toasts;
import com.ticketing.system.ui.components.kit.Lk;
import com.ticketing.system.ui.components.kit.LkBanner;
import com.ticketing.system.ui.components.kit.LkBtn;
import com.ticketing.system.ui.components.kit.LkCard;
import com.ticketing.system.ui.components.kit.LkCol;
import com.ticketing.system.ui.components.kit.LkCountdown;
import com.ticketing.system.ui.components.kit.LkIcon;
import com.ticketing.system.ui.components.kit.LkPage;
import com.ticketing.system.ui.components.kit.LkRow;
import com.ticketing.system.ui.layouts.MainLayout;
import com.ticketing.system.ui.presenters.order.CartPresenter;
import com.ticketing.system.ui.session.CartBadgeCache;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.auth.AnonymousAllowed;

import java.util.ArrayList;
import java.util.List;

@Route(value = "cart", layout = MainLayout.class)
@PageTitle("Cart · TicketHub")
@AnonymousAllowed
public class CartView extends LkPage {

    private final CartPresenter presenter;
    private CartPresenter.CartVM activeOrder;
    private long subtotalCents;

    private LkCol linesCol;
    /** Each line's Remove button, in line order, as the last rebuild made them. */
    private final List<LkBtn> removeButtons = new ArrayList<>();
    /** The empty-cart message, while the cart shows it. */
    private Component emptyState;
    private Span sub, total, subText;
    private LkBtn proceedBtn;

    public CartView(CartPresenter presenter) {
        this.presenter = presenter;

        switch (presenter.loadCart()) {
            case CartPresenter.LoadOutcome.Shown s -> {
                this.activeOrder   = s.cart();
                this.subtotalCents = s.subtotalCents();
            }
            case CartPresenter.LoadOutcome.Empty e             -> clearCart();
            case CartPresenter.LoadOutcome.NotAuthenticated na -> clearCart();
            case CartPresenter.LoadOutcome.Failure f -> {
                clearCart();
                Toasts.failure(Lk.withReason("Could not load your cart", f.reason()));
            }
        }

        title("Your Cart");
        renderHeaderSubtitle();
        add(buildSplit());
    }

    private void clearCart() {
        this.activeOrder   = null;
        this.subtotalCents = 0L;
    }

    private Component buildSplit() {
        Div split = new Div();
        split.addClassName("bz-cart-split");
        split.add(buildLineColumn(), buildSummaryCard());
        return split;
    }

    private Component buildLineColumn() {
        linesCol = new LkCol().gap(12);
        populateLines();
        return linesCol;
    }

    private void populateLines() {
        linesCol.removeAll();
        removeButtons.clear();
        emptyState = null;

        if (activeOrder == null || activeOrder.lines().isEmpty()) {
            emptyState = buildEmptyState();
            linesCol.add(emptyState);
        } else {
            linesCol.add(buildCountdownBanner(activeOrder.remainingSecondsBeforeExpiry()));
            for (CartPresenter.CartVM.LineVM line : activeOrder.lines()) {
                linesCol.add(cartLineFromVM(line));
            }
        }
    }

    private Component cartLineFromVM(CartPresenter.CartVM.LineVM line) {
        Div lineDiv = new Div();
        lineDiv.addClassName("bz-cartline");

        Div info = new Div();
        info.addClassName("bz-cartline-info");

        Span titleSpan = new Span();
        titleSpan.getElement().setProperty("innerHTML", "<b>" + escape(line.eventName()) + "</b>");

        Span seatLine = Lk.muted(line.seatNumber() != null ? line.seatNumber() : "General Admission");
        seatLine.getStyle().set("font-size", "13.5px");
        info.add(titleSpan, seatLine);

        // Every line repeats Remove, so it names its event and seat (#188).
        LkBtn removeBtn = new LkBtn("Remove")
            .variant(LkBtn.Variant.tertiary)
            .size(LkBtn.Size.s)
            .forRow(line.seatNumber() != null ? line.eventName() + ", " + line.seatNumber() : line.eventName())
            .onClick(e -> {
                int position = activeOrder.lines().indexOf(line);
                switch (presenter.removeLine(line)) {
                    case CartPresenter.RemoveOutcome.Removed r -> {
                        CartBadgeCache.invalidate();
                        this.activeOrder   = r.cart();
                        this.subtotalCents = r.subtotalCents();
                        populateLines();
                        renderHeaderSubtitle();
                        renderTotals();
                        focusAfterRemove(position);
                        Toasts.success("Removed from cart.");
                    }
                    case CartPresenter.RemoveOutcome.Failure f ->
                        Toasts.failure(Lk.withReason("Could not remove the item", f.reason()));
                }
            });

        removeButtons.add(removeBtn);
        info.add(removeBtn);
        lineDiv.add(info);

        Div priceTag = new Div();
        priceTag.addClassName("bz-cartline-price");
        priceTag.setText(Money.format(Money.toCents(line.pricePerTicket())));
        lineDiv.add(priceTag);

        return lineDiv;
    }

    // The rebuild removed the Remove button that had focus: focus the one now at its place, the next line's,
    // or the last line's when the last went; with the cart empty, the message saying so (#188, WCAG 2.4.3).
    private void focusAfterRemove(int position) {
        if (!removeButtons.isEmpty()) {
            removeButtons.get(Math.min(Math.max(position, 0), removeButtons.size() - 1)).focus();
        } else if (emptyState != null) {
            Lk.focusFromScript(emptyState);
        }
    }

    private Component buildCountdownBanner(long remainingSeconds) {
        LkBanner banner = new LkBanner();
        banner.setIcon(new LkIcon("clock", 17));

        Span timer = new Span();
        timer.getStyle().set("font-size", "16px").set("font-weight", "700");

        LkCountdown.start(timer.getElement(), remainingSeconds, "Time left: ");

        LkBanner heldBanner = new LkBanner(LkBanner.Tone.info, new LkIcon("lock", 17),
            "Tickets are held just for you. They release automatically when the timer runs out.");

        banner.setBody(timer);
        linesCol.add(heldBanner);
        return banner;
    }

    private Component buildEmptyState() {
        Span s = new Span("Your cart is empty. Browse events to add tickets.");
        s.getStyle()
            .set("display", "block").set("padding", "32px").set("text-align", "center")
            .set("color", "var(--muted)").set("background", "#fff")
            .set("border", "1px dashed var(--border-strong)").set("border-radius", "12px");
        return s;
    }

    private void renderHeaderSubtitle() {
        int n = (activeOrder != null) ? activeOrder.lines().size() : 0;
        subtitle(n == 0
            ? "No reservations held"
            : n + " reservation" + (n == 1 ? "" : "s") + " held · global timer active");
    }

    private Component buildSummaryCard() {
        LkCard card = new LkCard("Summary").pad(18);
        card.getStyle().set("align-self", "start").set("position", "sticky").set("top", "12px");

        LkCol col = new LkCol().gap(10);

        sub = new Span();
        total = new Span();
        subText = new Span();
        renderTotals();

        col.add(summaryRow("Subtotal", sub));
        col.add(Lk.divider());
        col.add(totalRow("Total", total));

        proceedBtn = new LkBtn("Proceed to Checkout").arrow(true)
            .variant(LkBtn.Variant.primary)
            .size(LkBtn.Size.l)
            .full()
            .onClick(e -> UI.getCurrent().navigate(CheckoutView.class));

        col.add(proceedBtn);
        col.add(subText);
        card.add(col);
        return card;
    }

    private void renderTotals() {
        boolean empty = activeOrder == null || activeOrder.lines().isEmpty();
        long shownTotal = empty ? 0L : subtotalCents;

        if (sub != null)   sub.setText(Money.format(shownTotal));
        if (total != null) total.getElement().setProperty("innerHTML",
            "<b style='font-size:17px'>" + Money.format(shownTotal) + "</b>");
        if (subText != null) {
            subText.setText(empty
                ? "Add tickets from Browse to start an order."
                : "One global timer covers the whole order");
            subText.getStyle()
                .set("text-align", "center").set("font-size", "12.5px")
                .set("color", "var(--muted)").set("display", "block");
        }
        if (proceedBtn != null) {
            proceedBtn.getElement().setEnabled(!empty);
            proceedBtn.getStyle().set("opacity", empty ? "0.55" : "1");
        }
    }

    private Component summaryRow(String label, Component value) {
        LkRow r = new LkRow().justify("space-between");
        r.add(Lk.muted(label), value);
        return r;
    }

    private Component totalRow(String label, Component value) {
        LkRow r = new LkRow().justify("space-between");
        Span l = new Span();
        l.getElement().setProperty("innerHTML", "<b style='font-size:17px'>" + label + "</b>");
        r.add(l, value);
        return r;
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}