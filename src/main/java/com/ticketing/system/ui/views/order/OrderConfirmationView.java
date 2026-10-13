package com.ticketing.system.ui.views.order;

import com.ticketing.system.shared.dto.CheckoutResultDTO;
import com.ticketing.system.ui.components.Money;
import com.ticketing.system.ui.components.kit.Lk;
import com.ticketing.system.ui.components.kit.LkBtn;
import com.ticketing.system.ui.components.kit.LkCard;
import com.ticketing.system.ui.components.kit.LkGrid;
import com.ticketing.system.ui.components.kit.LkIcon;
import com.ticketing.system.ui.components.kit.LkPage;
import com.ticketing.system.ui.components.kit.LkRow;
import com.ticketing.system.ui.layouts.MainLayout;
import com.ticketing.system.ui.session.OrderSession;
import com.ticketing.system.ui.views.account.MyAccountView;
import com.ticketing.system.ui.views.catalog.BrowseEventsView;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.auth.AnonymousAllowed;

import java.util.LinkedHashMap;
import java.util.Map;

@Route(value = "order-confirmed", layout = MainLayout.class)
@PageTitle("Order Confirmed · TicketHub")
@AnonymousAllowed
public class OrderConfirmationView extends LkPage implements BeforeEnterObserver {

    private CheckoutResultDTO result;

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        // Read-once: grab the result handed over by CheckoutView, then clear it so a
        // refresh / re-navigation reroutes to Browse instead of re-showing the receipt.
        result = OrderSession.result();
        OrderSession.clear();
        if (result == null) {
            event.rerouteTo(BrowseEventsView.class);
            return;
        }
        // Build the page here, not in the constructor: the constructor runs before
        // beforeEnter, when `result` is still null — building there would render an
        // empty receipt (no tickets, "—" ids/barcodes).
        add(buildConfirmHero());
        add(buildTicketsCard());
        add(buildActions());
    }

    public OrderConfirmationView() {
        // Intentionally empty — content is built in beforeEnter once `result` is set.
    }

    private Component buildConfirmHero() {
        Div hero = new Div();
        hero.addClassName("bz-confirm-hero");

        Div check = new Div();
        check.addClassName("bz-confirm-check");
        check.add(new LkIcon("check", 28, 2.4));

        Div text = new Div();
        H2 title = new H2("Your tickets are confirmed");
        title.getStyle().set("margin", "0").set("color", "#fff");

        String receiptDisplay = (result != null)
            ? "Receipt #TKT-" + String.format("%05d", result.orderReceiptId())
            : "Receipt pending";
        String totalDisplay = (result != null)
            ? Money.format(Money.toCents(result.totalCharged()))
            : "$0.00";
        // The real gateway transaction id (what WSEP `pay` returned), shown as-is so
        // it matches the receipt's "Transaction <externalTransactionId>" — not a
        // hex-mangled derivative.
        String transactionDisplay = (result != null)
            ? String.valueOf(result.paymentTransactionId())
            : "—";

        Span sub = new Span(receiptDisplay + " · Transaction " + transactionDisplay
            + " · " + totalDisplay + " paid · emailed to you");
        sub.getStyle().set("color", "rgba(255,255,255,0.9)").set("font-size", "14.5px");
        text.add(title, sub);

        hero.add(check, text);
        return hero;
    }

    private Component buildTicketsCard() {
        LkCard card = new LkCard("Your Tickets").pad(0);

        LkGrid grid = new LkGrid()
            .col("Ticket ID",  "ticketId")
            .col("Barcode",    "barcode");

        if (result != null && result.issuedTickets() != null && !result.issuedTickets().isEmpty()) {
            for (CheckoutResultDTO.IssuedTicketDTO ticket : result.issuedTickets()) {
                ticketRow(grid, ticket.ticketId(), ticket.barcode());
            }
        } else {
            ticketRow(grid, 0, null);
        }

        grid.build();
        card.add(grid);
        return card;
    }

    private void ticketRow(LkGrid grid, int ticketId, String barcodeValue) {
        Map<String, Object> row = new LinkedHashMap<>();

        if (ticketId > 0) {
            row.put("ticketId", String.valueOf(ticketId));
            // Show the barcode the issuer actually returned (real WSEP TIX code / stub code),
            // not a value fabricated from the ticket id.
            String value = (barcodeValue != null && !barcodeValue.isBlank()) ? barcodeValue : "Not yet issued";
            Span barcode = Lk.mono(value);
            barcode.addClassName("bz-barcode");
            row.put("barcode", barcode);
        } else {
            row.put("ticketId", "—");
            row.put("barcode", "—");
        }

        grid.row(row);
    }

    private Component buildActions() {
        LkRow row = new LkRow().gap(10);
        row.add(new LkBtn("View My Tickets")
            .variant(LkBtn.Variant.primary)
            .onClick(e -> UI.getCurrent().navigate(MyAccountView.class)));
        row.add(new LkBtn("Browse More Events")
            .variant(LkBtn.Variant.secondary)
            .onClick(e -> UI.getCurrent().navigate(BrowseEventsView.class)));
        return row;
    }
}