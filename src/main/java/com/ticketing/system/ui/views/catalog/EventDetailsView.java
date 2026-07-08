package com.ticketing.system.ui.views.catalog;

import com.ticketing.system.Core.Application.dto.EventDetailDTO;
import com.ticketing.system.Core.Application.dto.GridPlacementDTO;
import com.ticketing.system.Core.Application.dto.InventoryZoneDTO;
import com.ticketing.system.catalog.domain.EventStatus;
import com.ticketing.system.ui.components.kit.Lk;
import com.ticketing.system.ui.components.kit.LkBadge;
import com.ticketing.system.ui.components.kit.LkBanner;
import com.ticketing.system.ui.components.kit.LkBtn;
import com.ticketing.system.ui.components.kit.LkCard;
import com.ticketing.system.ui.components.kit.LkCol;
import com.ticketing.system.ui.components.kit.LkIcon;
import com.ticketing.system.ui.components.kit.LkPage;
import com.ticketing.system.ui.components.kit.LkRow;
import com.ticketing.system.ui.components.venue.VkSeatLegend;
import com.ticketing.system.ui.components.venue.VkVenueMap;
import com.ticketing.system.ui.layouts.MainLayout;
import com.ticketing.system.ui.presenters.catalog.EventDetailsPresenter;
import com.ticketing.system.ui.session.SessionIdentity;
import com.ticketing.system.ui.views.messaging.NewInquiryView;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.QueryParameters;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.auth.AnonymousAllowed;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Route(value = "events/:eventId", layout = MainLayout.class)
@PageTitle("Event · TicketHub")
@AnonymousAllowed
public class EventDetailsView extends LkPage implements BeforeEnterObserver {

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("EEE, d MMM yyyy · HH:mm", Locale.US);

    private final EventDetailsPresenter presenter;
    private final SessionIdentity sessionIdentity;

    private int eventId;
    private EventDetailDTO detail;
    private Double companyRating;
    private List<InventoryZoneDTO> zones = List.of();
    private int gridRows = 3;
    private int gridCols = 3;
    private Integer selectedZoneId = null;

    private final Div bodyHolder = new Div();
    private final Div mapContainer = new Div();
    private final Div zonesCardHolder = new Div();
    private Span selectedLine;
    private LkBtn chooseBtn;

    public EventDetailsView(EventDetailsPresenter presenter, SessionIdentity sessionIdentity) {
        this.presenter = presenter;
        this.sessionIdentity = sessionIdentity;
        add(bodyHolder);
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        this.eventId = event.getRouteParameters().get("eventId")
                .map(s -> { try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; } })
                .orElse(0);
        loadAndBuild();
    }

    private void loadAndBuild() {
        bodyHolder.removeAll();
        this.detail = null;
        this.companyRating = null;
        this.zones = List.of();
        this.selectedZoneId = null;

        if (eventId <= 0) {
            bodyHolder.add(infoBanner("This event isn't available."));
            return;
        }

        switch (presenter.load(sessionIdentity.credential(), eventId)) {
            case EventDetailsPresenter.Outcome.Success s -> {
                this.detail = s.event();
                this.companyRating = s.companyRating();
                this.zones = s.zones();
                this.gridRows = s.gridRows();
                this.gridCols = s.gridCols();
                this.selectedZoneId = firstSelectableZoneId();
                bodyHolder.add(buildHero(), buildSplit());
            }
            case EventDetailsPresenter.Outcome.NotFound nf ->
                bodyHolder.add(infoBanner(nf.message()));
            case EventDetailsPresenter.Outcome.Failure f ->
                bodyHolder.add(infoBanner(f.message()));
        }
    }

    // -------- hero (real event metadata: header, lineup, schedule, description, status badge) --------

    private Component buildHero() {
        Div hero = new Div();
        hero.addClassName("bz-evt-hero");

        Div poster = new Div();
        poster.addClassName("bz-evt-hero-poster");
        poster.getStyle().set("background", "linear-gradient(135deg, #8b5cf6, #ec4899)");
        poster.setText(posterText(detail.name()));

        Div meta = new Div();
        meta.addClassName("bz-evt-hero-meta");

        LkRow badges = new LkRow().gap(8);
        badges.add(statusBadge(detail.status()));
        if (detail.category() != null) {
            badges.add(new LkBadge(prettify(detail.category().toString()), LkBadge.Tone.muted).small());
        }
        if (detail.rating() != null) {
            badges.add(new LkBadge("★ " + ratingText(detail.rating()), LkBadge.Tone.muted).small());
        }

        Div title = new Div();
        title.addClassName("bz-evt-title");
        title.addClassName("lk-clamp-2");
        title.setText(detail.name());

        meta.add(badges, title);

        // Organizer (production company) name, with its DERIVED rating shown beside it when available.
        if (detail.companyName() != null && !detail.companyName().isBlank()) {
            String organizerLine = detail.companyName();
            if (companyRating != null) {
                organizerLine += "  ·  ★ " + ratingText(companyRating);
            }
            Span organizer = Lk.muted(organizerLine);
            organizer.getStyle().set("font-size", "14px").set("font-weight", "600");
            meta.add(organizer);
        }

        String lineup = detail.artistsNames() == null ? "" : String.join(" · ", detail.artistsNames());
        if (!lineup.isBlank()) {
            Span artists = new Span(lineup);
            artists.getStyle().set("font-size", "15px").set("font-weight", "600");
            meta.add(artists);
        }

        String when = scheduleLine();
        if (when != null) {
            Span schedule = Lk.muted(when);
            schedule.getStyle().set("font-size", "14px");
            meta.add(schedule);
        }

        if (detail.description() != null && !detail.description().isBlank()) {
            Span desc = Lk.muted(detail.description());
            desc.getStyle().set("font-size", "15px");
            meta.add(desc);
        }

        // Signed-in members can open an inquiry with the organizer, pre-filling this event's company.
        if (sessionIdentity.isMember() && detail.companyId() != null) {
            LkBtn ask = new LkBtn("Ask the Organizer")
                .variant(LkBtn.Variant.secondary)
                .icon(new LkIcon("comment", 15))
                .onClick(e -> UI.getCurrent().navigate(NewInquiryView.class,
                    QueryParameters.of("company", detail.companyId())));
            ask.getStyle().set("margin-top", "10px").set("align-self", "flex-start");
            meta.add(ask);
        }

        hero.add(poster, meta);
        return hero;
    }

    // -------- split: venue map + side rail --------

    private Component buildSplit() {
        Div split = new Div();
        split.addClassName("bz-evt-split");

        LkCard mapCard = new LkCard("Pick Your Area")
            .subtitle("Tap a zone to choose seats or quantity")
            .pad(16);

        mapContainer.removeAll();
        mapContainer.add(renderMap());
        mapCard.add(mapContainer);

        Div legendWrap = new Div();
        legendWrap.getStyle().set("margin-top", "12px");
        legendWrap.add(new VkSeatLegend());
        mapCard.add(legendWrap);

        LkCol rail = new LkCol().gap(14);
        rail.add(buildZonesCard(), buildReserveCard());

        split.add(mapCard, rail);
        return split;
    }

    private VkVenueMap renderMap() {
        List<VkVenueMap.Zone> vk = new ArrayList<>();
        for (int i = 0; i < zones.size(); i++) {
            InventoryZoneDTO z = zones.get(i);
            GridPlacementDTO p = z.getPlacement() != null ? z.getPlacement() : defaultPlacement(i);
            vk.add(new VkVenueMap.Zone(
                String.valueOf(z.getId()),
                z.getName(),
                "$" + (int) z.getPrice(),
                toneFor(z),
                noteFor(z),
                pct((p.row() - 1) * 100.0 / gridRows),
                pct((p.col() - 1) * 100.0 / gridCols),
                pct(p.colSpan() * 100.0 / gridCols),
                pct(p.rowSpan() * 100.0 / gridRows)));
        }
        String sel = selectedZoneId == null ? null : String.valueOf(selectedZoneId);
        return new VkVenueMap(vk, sel, false, this::selectZone);
    }

    private static String toneFor(InventoryZoneDTO z) {
        if (z.getAvailableAmount() == 0) {
            return "danger";
        }
        return z.getAvailableAmount() <= Math.max(1, z.getCapacity() / 10) ? "warn" : "ok";
    }

    private static String noteFor(InventoryZoneDTO z) {
        if (z.getAvailableAmount() == 0) {
            return "Sold out";
        }
        return "STANDING".equals(z.getZoneType()) ? "Standing" : null;
    }

    private void selectZone(String zoneIdStr) {
        InventoryZoneDTO z = zoneById(zoneIdStr);
        if (z == null || z.getAvailableAmount() == 0) {
            return;
        }
        selectedZoneId = z.getId();
        refreshMap();
        updateSelectedLine();
    }

    private void refreshMap() {
        mapContainer.removeAll();
        mapContainer.add(renderMap());
    }

    private void updateSelectedLine() {
        if (selectedLine == null) {
            return;
        }
        InventoryZoneDTO z = selectedZoneId == null ? null : zoneById(String.valueOf(selectedZoneId));
        String label = z == null ? "—" : z.getName();
        selectedLine.getElement().setProperty("innerHTML",
            "<span style='color:var(--muted);font-size:13px'>Selected: <b style='color:var(--text)'>" + escape(label) + "</b></span>");
    }

    private Component buildZonesCard() {
        LkCard card = new LkCard("Zones").pad(14);
        card.add(zonesCardHolder);
        refreshZonesCard();
        return card;
    }

    private void refreshZonesCard() {
        zonesCardHolder.removeAll();
        if (zones.isEmpty()) {
            Span hint = Lk.muted("No zones available for this event yet.");
            hint.getStyle().set("font-size", "13px");
            zonesCardHolder.add(hint);
            return;
        }
        LkCol col = new LkCol().gap(6);
        for (InventoryZoneDTO z : zones) {
            boolean soldOut = z.getAvailableAmount() == 0;
            String type = "STANDING".equals(z.getZoneType()) ? "standing" : "seated";
            col.add(zoneRow(z.getName() + " · " + type + (soldOut ? " · sold out" : ""),
                    "$" + (int) z.getPrice(), soldOut));
        }
        zonesCardHolder.add(col);
    }

    private Div zoneRow(String label, String price, boolean muted) {
        Div row = new Div();
        row.addClassName("bz-zonerow");
        if (muted) {
            row.addClassName("muted");
        }
        Span lbl = new Span(label);
        Span pr = new Span();
        pr.getElement().setProperty("innerHTML", "<b>" + price + "</b>");
        row.add(lbl, pr);
        return row;
    }

    private Component buildReserveCard() {
        LkCard card = new LkCard().pad(16).accent();
        selectedLine = new Span();
        updateSelectedLine();

        chooseBtn = new LkBtn("Choose Tickets →")
            .variant(LkBtn.Variant.primary)
            .size(LkBtn.Size.l)
            .full()
            .onClick(e -> openPicker());
        chooseBtn.getStyle().set("margin-top", "10px");
        Span hint = Lk.muted("Seats lock for 10 min once selected");
        hint.getStyle()
            .set("display", "block").set("margin-top", "8px")
            .set("text-align", "center").set("font-size", "12.5px");
        card.add(selectedLine, chooseBtn, hint);
        updateReserveState();
        return card;
    }

    /** Disable + relabel 'Choose tickets' for non-purchasable events (cancelled / ended / sold out). */
    private void updateReserveState() {
        if (chooseBtn == null) {
            return;
        }
        EventStatus status = detail == null ? null : detail.status();
        if (status == EventStatus.CANCELED) {
            chooseBtn.setEnabled(false);
            chooseBtn.label("Event Cancelled");
        } else if (status == EventStatus.COMPLETED) {
            chooseBtn.setEnabled(false);
            chooseBtn.label("Event Ended");
        } else if (status == EventStatus.SOLD_OUT || firstSelectableZoneId() == null) {
            chooseBtn.setEnabled(false);
            chooseBtn.label("Sold Out");
        } else {
            chooseBtn.setEnabled(true);
            chooseBtn.label("Choose Tickets →");
        }
    }

    private void openPicker() {
        if (eventId > 0 && selectedZoneId != null) {
            UI.getCurrent().navigate("events/" + eventId + "/seats/" + selectedZoneId);
        }
    }

    // -------- helpers --------

    private InventoryZoneDTO zoneById(String idStr) {
        try {
            int id = Integer.parseInt(idStr);
            return zones.stream().filter(z -> z.getId() == id).findFirst().orElse(null);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Integer firstSelectableZoneId() {
        return zones.stream().filter(z -> z.getAvailableAmount() > 0).findFirst()
                .map(InventoryZoneDTO::getId).orElse(null);
    }

    /** Auto-layout fallback for zones the owner never placed: flow into 1×1 cells by index. */
    private GridPlacementDTO defaultPlacement(int idx) {
        int row = Math.min((idx / Math.max(gridCols, 1)) + 1, gridRows);
        int col = (idx % Math.max(gridCols, 1)) + 1;
        return new GridPlacementDTO(row, col, 1, 1);
    }

    private static String posterText(String name) {
        if (name == null || name.isBlank()) {
            return "EVENT";
        }
        return name.trim().substring(0, 1).toUpperCase(Locale.US);
    }

    /** Rating shown without a trailing ".0" (e.g. 4.5 → "4.5", 4.0 → "4"). */
    private static String ratingText(double rating) {
        return rating == Math.floor(rating) ? String.valueOf((int) rating) : String.valueOf(rating);
    }

    private static LkBadge statusBadge(EventStatus status) {
        if (status == null) {
            return new LkBadge("—", LkBadge.Tone.muted).small();
        }
        return switch (status) {
            case ON_SALE   -> new LkBadge("ON SALE", LkBadge.Tone.success).small();
            case SOLD_OUT  -> new LkBadge("SOLD OUT", LkBadge.Tone.warning).small();
            case CANCELED  -> new LkBadge("CANCELLED", LkBadge.Tone.error).small();
            case COMPLETED -> new LkBadge("PAST", LkBadge.Tone.muted).small();
            default        -> new LkBadge(prettify(status.toString()), LkBadge.Tone.muted).small();
        };
    }

    private static String prettify(String enumName) {
        if (enumName == null || enumName.isBlank()) {
            return "";
        }
        String lower = enumName.replace('_', ' ').toLowerCase(Locale.US);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    private String scheduleLine() {
        StringBuilder sb = new StringBuilder();
        if (detail.showDates() != null && !detail.showDates().isEmpty()
                && detail.showDates().get(0).getStartTime() != null) {
            sb.append(DATE_FMT.format(detail.showDates().get(0).getStartTime()));
        }
        var loc = detail.location();
        if (loc != null) {
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append(loc.city()).append(", ").append(loc.country());
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private Component infoBanner(String message) {
        LkBanner banner = new LkBanner();
        banner.tone(LkBanner.Tone.info);
        banner.setIcon(new LkIcon("info", 18));
        banner.setBody(new Span(message));
        return banner;
    }

    private static String pct(double v) {
        return String.format(Locale.US, "%.2f%%", v);
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
