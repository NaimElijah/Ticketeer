package com.ticketing.system.ui.views.admin;

import com.ticketing.system.sales.application.dto.PurchaseHistoryDTO.PurchaseRecordDTO;
import com.ticketing.system.sales.application.dto.PurchaseHistoryDTO.RefundStatus;
import com.ticketing.system.reporting.application.dto.SalesSummaryDTO;
import com.ticketing.system.sales.application.dto.PurchaseHistoryDTO.TicketRecordDTO;
import com.ticketing.system.ui.components.kit.Lk;
import com.ticketing.system.ui.components.kit.LkBadge;
import com.ticketing.system.ui.components.kit.LkCard;
import com.ticketing.system.ui.components.kit.LkFilterChip;
import com.ticketing.system.ui.components.kit.LkGrid;
import com.ticketing.system.ui.components.kit.LkPage;
import com.ticketing.system.ui.components.kit.LkRow;
import com.ticketing.system.ui.components.kit.LkStat;
import com.ticketing.system.ui.layouts.PlatformAdminLayout;
import com.ticketing.system.ui.presenters.admin.GlobalHistoryPresenter;
import com.ticketing.system.ui.security.Capability;
import com.ticketing.system.ui.security.RequireCapability;
import com.ticketing.system.ui.support.DisplayTime;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.PermitAll;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Route(value = "admin/global-history", layout = PlatformAdminLayout.class)
@PageTitle("Global Purchase History · Admin")
@PermitAll
@RequireCapability(Capability.VIEW_GLOBAL_HISTORY)
public class GlobalHistoryView extends LkPage {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("d MMM yyyy");
    private static final String DATE_ALL = "All time";
    private static final String STATUS_PAID = "Paid";
    private static final String STATUS_PARTLY_REFUNDED = "Partly refunded";
    private static final String STATUS_REFUNDED = "Refunded";

    private final GlobalHistoryPresenter presenter;
    private final DisplayTime displayTime;
    private final List<PurchaseRecordDTO> records;

    private LkFilterChip dateChip;
    private LkFilterChip companyChip;
    private LkFilterChip eventChip;
    private LkFilterChip statusChip;
    private Div statsSlot;
    private Div gridSlot;

    public GlobalHistoryView(GlobalHistoryPresenter presenter, DisplayTime displayTime) {
        this.presenter = presenter;
        this.displayTime = displayTime;
        this.records = presenter.loadAllRecords();

        title("Global Purchase History");
        subtitle("Every order across the platform — filter by date, company, event, or status.");

        add(buildFilters());
        statsSlot = new Div();
        gridSlot = new Div();
        gridSlot.getStyle().set("width", "100%").set("min-width", "0");
        add(statsSlot, gridSlot);

        render();
    }

    private Component buildFilters() {
        LkRow row = new LkRow().gap(8);
        dateChip = new LkFilterChip("Date range",
                List.of("Last 7 days", "Last 30 days", "This quarter", "This year", DATE_ALL),
                true, List.of(DATE_ALL)).onApply(this::render);
        companyChip = new LkFilterChip("Company", distinct(this::companyOf), false, List.of()).onApply(this::render);
        eventChip = new LkFilterChip("Event", distinct(this::eventOf), false, List.of()).onApply(this::render);
        statusChip = new LkFilterChip("Status", List.of(STATUS_PAID, STATUS_PARTLY_REFUNDED, STATUS_REFUNDED), false, List.of()).onApply(this::render);
        row.add(dateChip, companyChip, eventChip, statusChip);
        return row;
    }

    private void render() {
        List<PurchaseRecordDTO> filtered = records.stream().filter(this::matches).toList();
        statsSlot.removeAll();
        statsSlot.add(buildStats(filtered));
        gridSlot.removeAll();
        gridSlot.add(buildGridCard(filtered));
    }

    // ---- filtering (client-side over the loaded, name-enriched records) ----

    private boolean matches(PurchaseRecordDTO r) {
        return matchesDate(r) && matchesCompany(r) && matchesEvent(r) && matchesStatus(r);
    }

    private boolean matchesDate(PurchaseRecordDTO r) {
        Set<String> sel = dateChip.getSelected();
        if (sel.isEmpty() || sel.contains(DATE_ALL)) return true;
        if (r.purchasedAt() == null) return false;
        // Calendar-correct windows ("This quarter"/"This year" start on the quarter/year boundary),
        // shared with the Company Sales page filter.
        // The range starts at midnight in the platform zone; purchase times are UTC (ADR-008).
        LocalDateTime from = displayTime.toRecorded(SalesDateRange.startOf(sel.iterator().next(), displayTime.today()));
        return from == null || !r.purchasedAt().isBefore(from);
    }

    private boolean matchesCompany(PurchaseRecordDTO r) {
        Set<String> sel = companyChip.getSelected();
        return sel.isEmpty() || r.tickets().stream().anyMatch(t -> sel.contains(companyOf(t)));
    }

    private boolean matchesEvent(PurchaseRecordDTO r) {
        Set<String> sel = eventChip.getSelected();
        return sel.isEmpty() || r.tickets().stream().anyMatch(t -> sel.contains(eventOf(t)));
    }

    private boolean matchesStatus(PurchaseRecordDTO r) {
        Set<String> sel = statusChip.getSelected();
        return sel.isEmpty() || sel.contains(statusLabel(r.refundStatus()));
    }

    // ---- stats + grid ----

    private Component buildStats(List<PurchaseRecordDTO> rs) {
        SalesSummaryDTO summary = presenter.summarize(rs);
        double revenue = summary.revenue();
        long orders = rs.size();
        long refunds = summary.refundedOrders();
        long tickets = summary.ticketsSold();

        Div stats = new Div();
        stats.addClassName("ow-stats");
        stats.add(
            new LkStat("Total revenue", money(revenue)),
            new LkStat("Orders",        String.valueOf(orders)),
            new LkStat("Refunds",       String.valueOf(refunds)),
            new LkStat("Tickets sold",  String.valueOf(tickets))
        );
        return stats;
    }

    private Component buildGridCard(List<PurchaseRecordDTO> rs) {
        LkCard card = new LkCard().pad(0);
        if (rs.isEmpty()) {
            card.add(Lk.muted("No orders match these filters."));
            return card;
        }
        LkGrid grid = new LkGrid()
            .col("Date",    "date")
            .col("Buyer",   "buyer")
            .col("Event",   "evt")
            .col("Company", "co")
            .col("Total",   "total",  LkGrid.Align.RIGHT)
            .col("Status",  "status", LkGrid.Align.RIGHT);

        rs.stream()
            .sorted(Comparator.comparing(PurchaseRecordDTO::purchasedAt,
                    Comparator.nullsLast(Comparator.naturalOrder())).reversed())
            .forEach(r -> recordRow(grid, r));

        grid.build();
        card.add(grid);
        return card;
    }

    private void recordRow(LkGrid grid, PurchaseRecordDTO r) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("date", r.purchasedAt() == null ? "—" : displayTime.format(r.purchasedAt(), DATE_FMT));
        row.put("buyer", Lk.mono(buyerLabel(r)));
        Span ev = new Span();
        ev.getElement().setProperty("innerHTML", "<b>" + escape(distinctLabel(r, this::eventOf, "events")) + "</b>");
        row.put("evt", ev);
        row.put("co", distinctLabel(r, this::companyOf, "companies"));
        Span t = new Span();
        t.getElement().setProperty("innerHTML", "<b>" + money(r.totalPaid()) + "</b>");
        row.put("total", t);
        row.put("status", statusBadge(statusLabel(r.refundStatus())));
        grid.row(row);
    }

    // ---- helpers ----

    private String companyOf(TicketRecordDTO t) {
        return orElse(t.companyName(), "Unknown company");
    }

    private String eventOf(TicketRecordDTO t) {
        return orElse(t.eventName(), "Event #" + t.eventId());
    }

    private static String buyerLabel(PurchaseRecordDTO r) {
        if (r.buyerName() != null && !r.buyerName().isBlank()) return r.buyerName();
        if (r.guestEmail() != null && !r.guestEmail().isBlank()) return r.guestEmail();
        return r.buyerUserId() == null ? "—" : "User #" + r.buyerUserId();
    }

    private List<String> distinct(java.util.function.Function<TicketRecordDTO, String> of) {
        return records.stream().flatMap(r -> r.tickets().stream())
                .map(of).filter(Objects::nonNull).distinct().sorted().toList();
    }

    private String distinctLabel(PurchaseRecordDTO r,
            java.util.function.Function<TicketRecordDTO, String> of, String plural) {
        List<String> values = r.tickets().stream().map(of).filter(Objects::nonNull).distinct().toList();
        if (values.isEmpty()) return "—";
        if (values.size() == 1) return values.get(0);
        return values.size() + " " + plural;
    }

    private static String money(double amount) {
        return String.format("$%,.2f", amount);
    }

    private static String orElse(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }

    private static String statusLabel(RefundStatus status) {
        return switch (status) {
            case NONE    -> STATUS_PAID;
            case PARTIAL -> STATUS_PARTLY_REFUNDED;
            case FULL    -> STATUS_REFUNDED;
        };
    }

    private LkBadge statusBadge(String status) {
        LkBadge.Tone tone = switch (status) {
            case STATUS_PAID            -> LkBadge.Tone.success;
            case STATUS_PARTLY_REFUNDED -> LkBadge.Tone.primary;
            case STATUS_REFUNDED        -> LkBadge.Tone.warn;
            default                     -> LkBadge.Tone.muted;
        };
        return new LkBadge(status, tone).small();
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
