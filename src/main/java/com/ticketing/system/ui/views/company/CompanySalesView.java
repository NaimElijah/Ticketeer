package com.ticketing.system.ui.views.company;

import com.ticketing.system.sales.application.dto.PurchaseHistoryDTO;
import com.ticketing.system.ui.components.Money;
import com.ticketing.system.ui.components.kit.Lk;
import com.ticketing.system.ui.components.kit.LkBadge;
import com.ticketing.system.ui.components.kit.LkBtn;
import com.ticketing.system.ui.components.kit.LkCard;
import com.ticketing.system.ui.components.kit.LkFilterChip;
import com.ticketing.system.ui.components.kit.LkGrid;
import com.ticketing.system.ui.components.kit.LkPage;
import com.ticketing.system.ui.components.kit.LkRow;
import com.ticketing.system.ui.components.kit.LkStat;
import com.ticketing.system.ui.layouts.WorkspaceLayout;
import com.ticketing.system.ui.presenters.company.CompanySalesPresenter;
import com.ticketing.system.ui.security.Capability;
import com.ticketing.system.ui.security.RequireCapability;
import com.ticketing.system.ui.session.AuthSession;
import com.ticketing.system.ui.session.CurrentCompanies;
import com.ticketing.system.ui.views.admin.SalesDateRange;
import com.ticketing.system.ui.views.admin.SalesSummary;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.PermitAll;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Route(value = "owner/sales", layout = WorkspaceLayout.class)
@PageTitle("Company Sales · TicketHub")
@PermitAll
@RequireCapability(Capability.VIEW_COMPANY_SALES)
public class CompanySalesView extends LkPage {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("d MMM yyyy");

    private final CompanySalesPresenter presenter;
    // Single body slot, rebuilt on every (re)load so the Refresh button can surface a
    // freshly-completed sale without a full page reload. Styled like the kit's .lk-page
    // body (flex column, 20px gap) because it replaces what used to be direct page children.
    private final Div content = new Div();

    // Stats + grid are rendered into these slots so the date filter can re-render from the
    // already-loaded records without re-querying the service.
    private final Div statsSlot = new Div();
    private final Div gridSlot = new Div();

    // The orders for the current company, loaded once per (re)load; the date filter narrows a copy.
    private List<PurchaseHistoryDTO.PurchaseRecordDTO> allRecords = List.of();
    // Default "All time" so every order (including refunded ones, which carry a tag) is visible by
    // default; the headline figures still count only non-refunded orders.
    private String selectedRange = SalesDateRange.ALL_TIME;
    private LkFilterChip dateChip;

    public CompanySalesView(CompanySalesPresenter presenter) {
        this.presenter = presenter;

        title("Company Sales History");
        actions(new LkBtn("Refresh").variant(LkBtn.Variant.secondary)
            .onClick(e -> reload()));

        content.getStyle().set("display", "flex").set("flex-direction", "column").set("gap", "20px");
        add(content);

        reload();
    }

    /** (Re)builds the report into the content slot — on open and on every Refresh click. */
    private void reload() {
        content.removeAll();
        switch (presenter.load(AuthSession.token(), CurrentCompanies.currentCompanyId())) {
            case CompanySalesPresenter.Outcome.Success ok -> {
                subtitle(ok.companyName() + "  ·  immutable order receipts.");
                this.allRecords = ok.sales().records();
                content.add(buildFilters());
                content.add(statsSlot);
                content.add(gridSlot);
                renderReport();
            }
            case CompanySalesPresenter.Outcome.NotAuthenticated ignored -> {
                subtitle("");
                content.add(Lk.muted("Your session has expired — please sign in again."));
            }
            case CompanySalesPresenter.Outcome.NoCompany ignored -> {
                subtitle("");
                content.add(Lk.muted("You're not part of a company workspace yet."));
            }
            case CompanySalesPresenter.Outcome.Failure fail -> {
                subtitle("");
                content.add(Lk.muted(Lk.withReason("Could not load sales", fail.reason())));
            }
        }
    }

    /** Filters the loaded records by the selected range and rebuilds the stats + grid slots. */
    private void renderReport() {
        List<PurchaseHistoryDTO.PurchaseRecordDTO> filtered = filterByDate(allRecords, selectedRange);
        statsSlot.removeAll();
        statsSlot.add(buildStats(filtered));
        gridSlot.removeAll();
        gridSlot.add(buildGridCard(filtered));
    }

    private static List<PurchaseHistoryDTO.PurchaseRecordDTO> filterByDate(
            List<PurchaseHistoryDTO.PurchaseRecordDTO> records, String range) {
        LocalDateTime from = SalesDateRange.startOf(range, LocalDate.now());
        if (from == null) {
            return records;
        }
        return records.stream()
                .filter(r -> r.purchasedAt() != null && !r.purchasedAt().isBefore(from))
                .toList();
    }

    private Component buildFilters() {
        dateChip = new LkFilterChip("Date range", SalesDateRange.OPTIONS, true, List.of(selectedRange));
        dateChip.onApply(() -> {
            selectedRange = dateChip.getSelected().stream().findFirst().orElse(SalesDateRange.ALL_TIME);
            renderReport();
        });
        LkRow row = new LkRow().gap(8);
        row.add(dateChip);
        return row;
    }

    private Component buildStats(List<PurchaseHistoryDTO.PurchaseRecordDTO> records) {
        SalesSummary s = SalesSummary.of(records);

        Div statsDiv = new Div();
        statsDiv.addClassName("ow-stats");
        statsDiv.add(
            new LkStat("Total revenue",   Money.format(Money.toCents(s.revenue()))),
            new LkStat("Tickets sold",     String.valueOf(s.ticketsSold())),
            new LkStat("Avg order value",  Money.format(Money.toCents(s.avgOrderValue()))),
            new LkStat("Top event",        s.topEvent())
        );
        return statsDiv;
    }

    private Component buildGridCard(List<PurchaseHistoryDTO.PurchaseRecordDTO> records) {
        LkCard card = new LkCard().pad(0);
        LkGrid grid = new LkGrid()
            .col("Date",    "date")
            .col("Event",   "evt")
            .col("Buyer",   "buyer")
            .col("Tickets", "tickets", LkGrid.Align.RIGHT)
            .col("Total",   "total",   LkGrid.Align.RIGHT);

        if (records.isEmpty()) {
            card.add(Lk.muted("No sales yet."));
            return card;
        }

        for (PurchaseHistoryDTO.PurchaseRecordDTO r : records) {
            Map<String, Object> row = new LinkedHashMap<>();

            String date = r.purchasedAt() != null
                    ? r.purchasedAt().format(DATE_FMT) : "—";
            row.put("date", date);

            String eventName = r.tickets().stream()
                    .map(PurchaseHistoryDTO.TicketRecordDTO::eventName)
                    .filter(n -> n != null)
                    .findFirst().orElse("—");
            Span ev = new Span();
            ev.getElement().setProperty("innerHTML", "<b>" + escape(eventName) + "</b>");
            row.put("evt", ev);

            String buyer = r.buyerName() != null ? r.buyerName()
                    : r.guestEmail() != null ? r.guestEmail() : "Guest";
            row.put("buyer", Lk.mono(buyer));

            row.put("tickets", String.valueOf(r.tickets().size()));

            row.put("total", totalCell(r));

            grid.row(row);
        }

        grid.build();
        card.add(grid);
        return card;
    }

    /**
     * Total amount for an order. A refunded order is shown struck-through with a "Refunded" tag so
     * it's clear it does not contribute to the headline revenue (see {@link SalesSummary}).
     */
    private static Component totalCell(PurchaseHistoryDTO.PurchaseRecordDTO r) {
        String amount = Money.format(Money.toCents(r.totalPaid()));
        Span amountSpan = new Span();
        LkRow cell = new LkRow().gap(8).noWrap().justify("flex-end");
        if (r.refunded()) {
            amountSpan.getElement().setProperty("innerHTML",
                    "<span style=\"text-decoration:line-through;color:var(--muted);\">" + amount + "</span>");
            cell.add(amountSpan, new LkBadge("Refunded", LkBadge.Tone.error).small());
        } else {
            amountSpan.getElement().setProperty("innerHTML", "<b>" + amount + "</b>");
            cell.add(amountSpan);
        }
        return cell;
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
