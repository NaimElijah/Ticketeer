package com.ticketing.system.Presentation.views.catalog;

import java.util.List;

import com.ticketing.system.Core.Application.dto.InventoryZoneDTO;
import com.ticketing.system.catalog.domain.ZoneType;
import com.ticketing.system.Presentation.components.Toasts;
import com.ticketing.system.Presentation.components.kit.Lk;
import com.ticketing.system.Presentation.components.kit.LkBanner;
import com.ticketing.system.Presentation.components.kit.LkBtn;
import com.ticketing.system.Presentation.components.kit.LkCard;
import com.ticketing.system.Presentation.components.kit.LkCol;
import com.ticketing.system.Presentation.components.kit.LkIcon;
import com.ticketing.system.Presentation.components.kit.LkPage;
import com.ticketing.system.Presentation.components.kit.LkRow;
import com.ticketing.system.Presentation.components.venue.VkQuantitySelector;
import com.ticketing.system.Presentation.components.venue.VkSeat;
import com.ticketing.system.Presentation.components.venue.VkSeatLegend;
import com.ticketing.system.Presentation.components.venue.VkSeatedZonePicker;
import com.ticketing.system.Presentation.components.venue.VkStandingZone;
import com.ticketing.system.Presentation.layouts.MainLayout;
import com.ticketing.system.Presentation.presenters.catalog.SeatPickerPresenter;
import com.ticketing.system.Presentation.views.order.CartView;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.auth.AnonymousAllowed;

/**
 * Zone picker (V2-CAT-03 / V2-RES-01 / V2-RES-02). Loads the selected zone via presenter
 * and renders the picker matching its type — {@link VkSeatedZonePicker} for SEATED,
 * {@link VkQuantitySelector} for STANDING.
 */
@Route(value = "events/:eventId/seats/:zoneId", layout = MainLayout.class)
@PageTitle("Pick Tickets · TicketHub")
@AnonymousAllowed
public class SeatPickerView extends LkPage implements BeforeEnterObserver {

    // Derived from the tile size so seats never overlap or drift — one source of truth.
    private static final int SEAT_STEP = VkSeatedZonePicker.TILE_PX + 6;

    private final SeatPickerPresenter presenter;

    private int eventId;
    private int zoneId;
    private InventoryZoneDTO zone;
    private double zonePrice;

    private VkSeatedZonePicker seatPicker;
    private LkCol selectionListCol;
    private VkQuantitySelector qtySelector;
    private LkBtn holdButton;
    private final Div bodyHolder = new Div();

    public SeatPickerView(SeatPickerPresenter presenter) {
        this.presenter = presenter;
        add(bodyHolder);
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        this.eventId = parseId(event.getRouteParameters().get("eventId").orElse("0"));
        this.zoneId = parseId(event.getRouteParameters().get("zoneId").orElse("0"));
        loadAndBuild();
    }

    private void loadAndBuild() {
        bodyHolder.removeAll();
        seatPicker = null;
        qtySelector = null;
        holdButton = null;

        SeatPickerPresenter.LoadOutcome outcome = presenter.loadZone(eventId, zoneId);
        switch (outcome) {
            case SeatPickerPresenter.LoadOutcome.Loaded l -> {
                this.zone = l.zone();
                this.zonePrice = l.zone().getPrice();
                bodyHolder.add(buildBody(l.zoneType()));
            }
            case SeatPickerPresenter.LoadOutcome.NotFound nf ->
                bodyHolder.add(infoBanner(nf.message()));
            case SeatPickerPresenter.LoadOutcome.Failure f ->
                bodyHolder.add(infoBanner(f.message()));
        }
    }

    private Component buildBody(ZoneType zoneType) {
        return switch (zoneType) {
            case SEATED -> buildSeatedPicker();
            case STANDING -> buildStandingPicker();
        };
    }

    private Component buildSeatedPicker() {
        seatPicker = new VkSeatedZonePicker(
        SeatPickerPresenter.buildSeatModels(zone, SEAT_STEP),
        this::updateSelectionRail);
        Div wrap = new Div();
        wrap.add(buildBreadcrumb(zone.getName() + " · seated"));

        Div split = new Div();
        split.addClassName("vz-split");
        split.add(buildSeatCanvas(), buildSelectionRail());
        wrap.add(split);

        Div ref = new Div();
        ref.addClassName("vz-split2");
        ref.add(buildLockingExplainerCard(), buildStatesReferenceCard());
        wrap.add(ref);
        return wrap;
    }
    

    private Component buildSeatCanvas() {
        LkCard card = new LkCard().pad(0);

        Div header = new Div();
        header.addClassName("vz-canvas-h");
        Span name = new Span();
        name.getElement().setProperty("innerHTML", "<b>" + escape(zone.getName()) + "</b>");
        Span sub = Lk.muted(" · click a green seat to select");
        sub.getStyle().set("font-size", "13px");
        Div title = new Div();
        title.add(name, sub);
        header.add(title);
        card.add(header);

        Div canvas = new Div();
        canvas.addClassName("vz-canvas");
        Div stageStrip = new Div();
        stageStrip.addClassName("vz-stage-strip");
        stageStrip.setText("◄ FACING STAGE ►");
        canvas.add(stageStrip);

        Div seatScroll = new Div();
        seatScroll.addClassName("vz-seatscroll");
        seatScroll.add(seatPicker);
        canvas.add(seatScroll);

        Div legendWrap = new Div();
        legendWrap.getStyle().set("padding", "0 18px 16px");
        legendWrap.add(new VkSeatLegend());
        canvas.add(legendWrap);

        card.add(canvas);
        return card;
    }

    private Component buildSelectionRail() {
        LkCol rail = new LkCol().gap(14);
        LkCard selection = new LkCard("Your Selection").pad(16);
        selectionListCol = new LkCol().gap(10);
        selection.add(selectionListCol);
        rail.add(selection);
        updateSelectionRail();
        return rail;
    }

    private void updateSelectionRail() {
        if (selectionListCol == null || seatPicker == null) {
            return;
        }
        selectionListCol.removeAll();
        List<String> labels = seatPicker.hasSelection()
                ? seatPicker.getSelection().getSeatNumbers() : List.of();
        if (labels.isEmpty()) {
            Span hint = Lk.muted("Click a green seat above to select.");
            hint.getStyle().set("text-align", "center").set("display", "block").set("padding", "8px 0");
            selectionListCol.add(hint);
            return;
        }
        Div list = new Div();
        list.addClassName("vz-sellist");
        for (String label : labels) {
            list.add(selectedSeatRow(label));
        }
        selectionListCol.add(list);
        selectionListCol.add(Lk.divider());

        double total = labels.size() * zonePrice;
        LkRow totalRow = new LkRow().justify("space-between");
        totalRow.add(Lk.muted(labels.size() + " seat" + (labels.size() == 1 ? "" : "s")));
        Span totalDisplay = new Span();
        totalDisplay.getElement().setProperty("innerHTML", "<b style='font-size:16px'>" + money(total) + "</b>");
        totalRow.add(totalDisplay);
        selectionListCol.add(totalRow);

        selectionListCol.add(new LkBtn("Add to Cart →")
            .variant(LkBtn.Variant.primary).size(LkBtn.Size.l).full()
            .onClick(e -> reserveSeated()));
    }

    private Component selectedSeatRow(String label) {
        Div row = new Div();
        row.addClassName("vz-selseat");
        Div info = new Div();
        Span name = new Span();
        name.getElement().setProperty("innerHTML", "<b>Seat " + escape(label) + "</b>");
        Span subline = Lk.muted(zone.getName() + " · " + money(zonePrice));
        subline.getStyle().set("font-size", "12.5px").set("display", "block");
        info.add(name, subline);

        Span remove = new Span();
        remove.addClassName("vz-selseat-x");
        remove.add(new LkIcon("close", 15));
        remove.getElement().addEventListener("click", e -> {
            if (seatPicker != null) {
                seatPicker.deselect(label);
            }
        });
        row.add(info, remove);
        return row;
    }

    private void reserveSeated() {
        if (seatPicker == null || !seatPicker.hasSelection()) {
            return;
        }
        var selection = seatPicker.getSelection();
        int count = selection.getSeatNumbers().size();
        
        SeatPickerPresenter.ReserveOutcome outcome = presenter.reserveSeated(selection, eventId, zoneId);
        switch (outcome) {
            case SeatPickerPresenter.ReserveOutcome.Success s -> {
                seatPicker.clearSelection();
                Toasts.success(count + " seat" + (count == 1 ? "" : "s") + " reserved — continue to cart.");
                UI.getCurrent().navigate(CartView.class);
            }
            case SeatPickerPresenter.ReserveOutcome.Failure f ->
                Toasts.failure(reserveFailureMessage(f.message()));
        }
    }

    private Component buildStandingPicker() {
        Div wrap = new Div();
        wrap.add(buildBreadcrumb(zone.getName() + " · standing"));

        LkCard card = new LkCard("General Admission · Standing").pad(18);
        LkCol col = new LkCol().gap(16);
        col.add(new VkStandingZone(zone.getName(), zone.getSoldAmount(), zone.getReservedAmount(),
                zone.getCapacity(), money(zonePrice) + " each"));

        qtySelector = new VkQuantitySelector(
                Math.max(0, zone.getAvailableAmount()),
                (int) Math.round(zonePrice * 100),
                this::updateHoldButton);
        col.add(qtySelector);
        col.add(Lk.divider());

        holdButton = new LkBtn("Hold Tickets →").variant(LkBtn.Variant.primary).full()
                .onClick(e -> reserveStanding());
        col.add(holdButton);
        updateHoldButton();

        card.add(col);
        wrap.add(card);
        return wrap;
    }

    private void updateHoldButton() {
        if (holdButton == null || qtySelector == null) {
            return;
        }
        int q = qtySelector.getQuantity();
        holdButton.setEnabled(qtySelector.hasSelection());
        holdButton.setText(qtySelector.hasSelection()
                ? "Hold " + q + " ticket" + (q == 1 ? "" : "s") + " →"
                : "Sold out");
    }

    private void reserveStanding() {
        if (qtySelector == null || !qtySelector.hasSelection()) {
            Toasts.failure("No tickets available.");
            return;
        }
        int q = qtySelector.getQuantity();
        
        SeatPickerPresenter.ReserveOutcome outcome = presenter.reserveStanding(q, eventId, zoneId);
        switch (outcome) {
            case SeatPickerPresenter.ReserveOutcome.Success s -> {
                Toasts.success(q + " ticket" + (q == 1 ? "" : "s") + " reserved — continue to cart.");
                UI.getCurrent().navigate(CartView.class);
            }
            case SeatPickerPresenter.ReserveOutcome.Failure f ->
                Toasts.failure(reserveFailureMessage(f.message()));
        }
    }

    /** Surface the real reserve-failure reason when present, else a friendly fallback. */
    private static String reserveFailureMessage(String raw) {
        return (raw == null || raw.isBlank())
            ? "Couldn't reserve those tickets — please try again."
            : raw;
    }

    private Component buildBreadcrumb(String trailText) {
        Div crumb = new Div();
        crumb.addClassName("vz-crumb");
        Span back = new Span("← Back to areas");
        back.addClassName("bz-link");
        back.getElement().addEventListener("click", e -> UI.getCurrent().navigate("events/" + eventId));
        Span trail = Lk.muted(" / " + trailText + " · " + money(zonePrice));
        trail.getStyle().set("font-size", "13px");
        crumb.add(back, trail);
        return crumb;
    }

    private Component buildLockingExplainerCard() {
        LkCard card = new LkCard("How Locking Works").pad(16);
        LkCol col = new LkCol().gap(9);
        col.add(lockLegendRow("mine", "<b>In your order</b> — held for you for 10 minutes."));
        col.add(lockLegendRow("held", "<b>Locked by others</b> — another buyer is mid-purchase."));
        col.add(lockLegendRow("sold", "<b>Sold</b> — already purchased."));
        Span fine = Lk.muted("Two buyers can never hold the same seat — first tap wins.");
        fine.getStyle().set("font-size", "12px").set("margin-top", "2px").set("display", "block");
        col.add(fine);
        card.add(col);
        return card;
    }

    private Component lockLegendRow(String stateClass, String html) {
        LkRow row = new LkRow().gap(10).align("flex-start");
        Span swatch = new Span();
        swatch.addClassName("vk-seat");
        swatch.addClassName("vk-seat-" + stateClass);
        swatch.addClassName("vk-legend-swatch");
        Span text = new Span();
        text.getElement().setProperty("innerHTML", html);
        text.getStyle().set("font-size", "13px");
        row.add(swatch, text);
        return row;
    }

    private Component buildStatesReferenceCard() {
        LkCard card = new LkCard("Race-Condition States")
            .subtitle("What the seat colours mean under load")
            .pad(18);
        Div grid = new Div();
        grid.addClassName("vz-state-grid");
        grid.add(stateCell(VkSeat.State.free, "", "Available", "Tap to hold"));
        grid.add(stateCell(VkSeat.State.mine, "12", "In your order", "Locked 10 min"));
        grid.add(stateCell(VkSeat.State.held, "12", "Locked by others", "Live update"));
        grid.add(stateCell(VkSeat.State.sold, "12", "Sold", "Unavailable"));
        card.add(grid);
        return card;
    }

    private Div stateCell(VkSeat.State state, String num, String title, String sub) {
        Div cell = new Div();
        cell.addClassName("vz-state-cell");
        VkSeat seat = new VkSeat(state, num);
        Div meta = new Div();
        Span t = new Span();
        t.getElement().setProperty("innerHTML", "<b style='font-size:13.5px'>" + title + "</b>");
        Span s = Lk.muted(sub);
        s.getStyle().set("font-size", "12px").set("display", "block");
        meta.add(t, s);
        cell.add(seat, meta);
        return cell;
    }

    private Component infoBanner(String message) {
        LkBanner banner = new LkBanner();
        banner.tone(LkBanner.Tone.info);
        banner.setIcon(new LkIcon("info", 18));
        banner.setBody(new Span(message));
        return banner;
    }

    private static int parseId(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String money(double v) {
        return String.format(java.util.Locale.US, "$%,.2f", v);
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}