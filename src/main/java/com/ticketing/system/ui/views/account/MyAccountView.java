package com.ticketing.system.ui.views.account;

import com.ticketing.system.sales.application.dto.PurchaseHistoryDTO;
import com.ticketing.system.sales.application.dto.PurchaseHistoryDTO.PurchaseRecordDTO;
import com.ticketing.system.sales.application.dto.PurchaseHistoryDTO.TicketRecordDTO;
import com.ticketing.system.sales.domain.TicketStatus;
import com.ticketing.system.ui.components.Toasts;
import com.ticketing.system.ui.components.buyer.BzRefundDialog;
import com.ticketing.system.ui.components.buyer.BzTicketDialog;
import com.ticketing.system.ui.components.kit.Lk;
import com.ticketing.system.ui.components.kit.LkCard;
import com.ticketing.system.ui.components.kit.LkGrid;
import com.ticketing.system.ui.components.kit.LkIcon;
import com.ticketing.system.ui.components.kit.LkIconBtn;
import com.ticketing.system.ui.components.kit.LkPage;
import com.ticketing.system.ui.components.kit.LkStatusDot;
import com.ticketing.system.ui.layouts.MainLayout;
import com.ticketing.system.ui.presenters.account.MyAccountPresenter;
import com.ticketing.system.ui.presenters.account.RefundPresenter;
import com.ticketing.system.ui.session.AuthSession;
import com.ticketing.system.ui.session.SessionIdentity;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.NativeButton;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.PermitAll;

import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Route(value = "my-account", layout = MainLayout.class)
@PageTitle("My Account · TicketHub")
@PermitAll
public class MyAccountView extends LkPage {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("d MMM yyyy");
    private static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("EEE d MMM yyyy · HH:mm");

    private final PurchaseHistoryDTO history;
    private final RefundPresenter refundPresenter;
    private final SessionIdentity sessionIdentity;

    public MyAccountView(MyAccountPresenter presenter, RefundPresenter refundPresenter,
                         SessionIdentity sessionIdentity) {
        this.history = presenter.loadHistory();
        this.refundPresenter = refundPresenter;
        this.sessionIdentity = sessionIdentity;

        add(buildHero());
        add(Lk.h2("My Orders"));
        add(buildOrdersCard());
        add(Lk.h2("My Tickets"));
        add(buildTicketsCard());
    }

    private Component buildHero() {
        String name = AuthSession.displayName();
        if (name == null || name.isBlank()) name = "there";
        String first = name.split("\\s+")[0];

        Div hero = new Div();
        hero.addClassName("acct-hero");

        Span avatar = new Span(initials(name));
        avatar.addClassName("acct-hero-av");

        Div meta = new Div();
        H2 welcome = new H2("Welcome back, " + first);
        welcome.getStyle().set("margin", "0").set("color", "#fff");
        Span sub = new Span("Track your orders, view tickets, and request refunds.");
        sub.getStyle().set("color", "rgba(255,255,255,0.9)").set("font-size", "14.5px");
        meta.add(welcome, sub);

        int orderCount = history.records().size();
        long ticketCount = history.records().stream().mapToLong(r -> r.tickets().size()).sum();

        Div stats = new Div();
        stats.addClassName("acct-hero-stats");
        stats.add(statBlock(String.valueOf(orderCount), orderCount == 1 ? "order" : "orders"),
                statBlock(String.valueOf(ticketCount), ticketCount == 1 ? "ticket" : "tickets"));

        hero.add(avatar, meta, stats);
        return hero;
    }

    private Div statBlock(String value, String label) {
        Div d = new Div();
        Span v = new Span();
        v.getElement().setProperty("innerHTML", "<b>" + value + "</b>");
        Span l = new Span(label);
        d.add(v, l);
        return d;
    }

    private Component buildOrdersCard() {
        LkCard card = new LkCard().pad(0);
        if (history.records().isEmpty()) {
            card.add(Lk.muted("No orders yet — your purchases will appear here."));
            return card;
        }
        LkGrid grid = new LkGrid()
            .col("Order",   "id")
            .col("Event",   "evt")
            .col("Date",    "date")
            .col("Total",   "total", LkGrid.Align.RIGHT)
            .col("Status",  "status")
            .col("Receipt", "receipt", LkGrid.Align.RIGHT);

        history.records().stream()
            .sorted(Comparator.comparing(PurchaseRecordDTO::purchasedAt,
                    Comparator.nullsLast(Comparator.naturalOrder())).reversed())
            .forEach(r -> orderRow(grid, r));

        grid.build();
        card.add(grid);
        return card;
    }

    private void orderRow(LkGrid grid, PurchaseRecordDTO r) {
        Map<String, Object> row = new LinkedHashMap<>();
        Span idSpan = new Span();
        idSpan.addClassName("lk-mono");
        idSpan.getElement().setProperty("innerHTML", "<b>#" + r.orderReceiptId() + "</b>");
        row.put("id", idSpan);
        row.put("evt", orderEventLabel(r));
        row.put("date", r.purchasedAt() == null ? "—" : r.purchasedAt().format(DATE_FMT));
        row.put("total", money(r.totalPaid()));
        boolean refunded = r.refunded();
        row.put("status", new LkStatusDot(refunded ? LkStatusDot.Tone.muted : LkStatusDot.Tone.ok,
                refunded ? "Refunded" : "Paid"));

        NativeButton view = new NativeButton("View");
        view.addClassName("bz-link");
        view.getStyle().set("background", "none").set("border", "none").set("padding", "0").set("cursor", "pointer");
        view.addClickListener(e -> UI.getCurrent().navigate("receipt/" + r.orderReceiptId()));

        if (refundEligible(r)) {
            NativeButton refund = new NativeButton("Request Refund");
            refund.addClassName("bz-link");
            refund.getStyle().set("background", "none").set("border", "none").set("padding", "0")
                .set("cursor", "pointer").set("margin-left", "14px");
            refund.addClickListener(e -> BzRefundDialog.open("#" + r.orderReceiptId(), r.totalPaid(),
                reason -> handleRefund(r.orderReceiptId(), reason)));
            Div cell = new Div(view, refund);
            row.put("receipt", cell);
        } else {
            row.put("receipt", view);
        }
        grid.row(row);
    }

    private static boolean refundEligible(PurchaseRecordDTO r) {
        if (r.refunded() || r.buyerUserId() == null) {
            return false;   // already refunded, or a guest order (member-only flow)
        }
        return r.tickets().stream().anyMatch(t ->
            t.currentStatus() == TicketStatus.PAID || t.currentStatus() == TicketStatus.ISSUED);
    }

    private void handleRefund(int orderId, String reason) {
        switch (refundPresenter.requestRefund(sessionIdentity.memberToken(), orderId, reason)) {
            case RefundPresenter.Outcome.Success s -> {
                Toasts.success("Refund requested — reference " + s.reference()
                    + ". Processed in 3–5 business days.");
                UI.getCurrent().getPage().reload();   // refresh orders + tickets
            }
            case RefundPresenter.Outcome.NotEligible n -> Toasts.warn(n.message());
            case RefundPresenter.Outcome.NotFound n    -> Toasts.failure(n.message());
            case RefundPresenter.Outcome.Forbidden f   -> Toasts.failure(f.message());
            case RefundPresenter.Outcome.Failure f     -> Toasts.failure(f.message());
        }
    }

    private Component buildTicketsCard() {
        LkCard card = new LkCard().pad(0);
        // Refunded / voided tickets are no longer usable, so they drop out of "My Tickets"
        // (the order still shows as Refunded under "My Orders").
        List<TicketRecordDTO> tickets = history.records().stream()
            .flatMap(r -> r.tickets().stream())
            .filter(t -> t.currentStatus() != TicketStatus.REFUNDED
                      && t.currentStatus() != TicketStatus.VOIDED)
            .toList();
        if (tickets.isEmpty()) {
            card.add(Lk.muted("No tickets yet."));
            return card;
        }
        LkGrid grid = new LkGrid()
            .col("Event",  "evt")
            .col("Zone",   "zone")
            .col("Seat",   "seat")
            .col("Price",  "price", LkGrid.Align.RIGHT)
            .col("Status", "status")
            .col("",       "act", LkGrid.Align.RIGHT);
        tickets.forEach(t -> ticketRow(grid, t));
        grid.build();
        card.add(grid);
        return card;
    }

    private void ticketRow(LkGrid grid, TicketRecordDTO t) {
        Map<String, Object> row = new LinkedHashMap<>();
        Span ev = new Span();
        ev.getElement().setProperty("innerHTML", "<b>" + escape(orElse(t.eventName(), "Event #" + t.eventId())) + "</b>");
        row.put("evt", ev);
        row.put("zone", orElse(t.zoneName(), "Zone #" + t.zoneId()));
        row.put("seat", orElse(t.seatNumber(), "—"));
        row.put("price", money(t.pricePaid()));
        TicketStatus st = t.currentStatus();
        row.put("status", new LkStatusDot(statusTone(st), prettyStatus(st)));

        LkIconBtn viewBtn = new LkIconBtn(new LkIcon("eye", 15), "View ticket + barcode");
        viewBtn.addClickListener(e -> BzTicketDialog.show(toTicketInfo(t)));
        row.put("act", viewBtn);
        grid.row(row);
    }

    private static BzTicketDialog.TicketInfo toTicketInfo(TicketRecordDTO t) {
        return new BzTicketDialog.TicketInfo(
            orElse(t.eventName(), "Event #" + t.eventId()),
            orElse(t.category(), "—"),
            t.eventStartsAt() == null ? "—" : t.eventStartsAt().format(DATETIME_FMT),
            orElse(t.venue(), "—"),
            orElse(t.zoneName(), "Zone #" + t.zoneId()),
            orElse(t.seatNumber(), "—"),
            money(t.pricePaid()),
            orElse(t.barcode(), "Not yet issued"),
            "#" + t.orderReceiptId());
    }

    // ---- helpers ----

    private String orderEventLabel(PurchaseRecordDTO r) {
        List<String> names = r.tickets().stream()
            .map(t -> orElse(t.eventName(), "Event #" + t.eventId()))
            .distinct().toList();
        if (names.isEmpty()) return "—";
        if (names.size() == 1) return names.get(0);
        return names.size() + " events";
    }

    private static String money(double amount) {
        return String.format("$%,.2f", amount);
    }

    private static String orElse(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }

    private static LkStatusDot.Tone statusTone(TicketStatus st) {
        if (st == null) return LkStatusDot.Tone.ok;
        return switch (st) {
            case PAID, ISSUED -> LkStatusDot.Tone.ok;
            case USED -> LkStatusDot.Tone.warn;
            case REFUNDED, VOIDED -> LkStatusDot.Tone.muted;
            default -> LkStatusDot.Tone.ok;
        };
    }

    private static String prettyStatus(TicketStatus st) {
        if (st == null) return "—";
        String s = st.name().toLowerCase().replace('_', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String initials(String name) {
        if (name == null || name.isBlank()) return "??";
        String[] parts = name.trim().split("\\s+");
        if (parts.length == 1) {
            String p = parts[0];
            return p.substring(0, Math.min(2, p.length())).toUpperCase();
        }
        return ("" + parts[0].charAt(0) + parts[parts.length - 1].charAt(0)).toUpperCase();
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
