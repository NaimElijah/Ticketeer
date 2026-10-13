package com.ticketing.system.ui.views.company;

import com.ticketing.system.shared.dto.GridPlacementDTO;
import com.ticketing.system.shared.dto.VenueMapConfigDTO;
import com.ticketing.system.shared.dto.ZoneDetailDTO;
import com.ticketing.system.catalog.domain.SeatLabels;
import com.ticketing.system.ui.components.Toasts;
import com.ticketing.system.ui.components.kit.Lk;
import com.ticketing.system.ui.components.kit.LkBadge;
import com.ticketing.system.ui.components.kit.LkBtn;
import com.ticketing.system.ui.components.kit.LkCard;
import com.ticketing.system.ui.components.kit.LkCol;
import com.ticketing.system.ui.components.kit.LkPage;
import com.ticketing.system.ui.components.kit.LkRow;
import com.ticketing.system.ui.components.venue.VkSeatBlock;
import com.ticketing.system.ui.components.venue.VkVenueMap;
import com.ticketing.system.ui.layouts.WorkspaceLayout;
import com.ticketing.system.ui.presenters.company.VenueMapPresenter;
import com.ticketing.system.ui.security.Capability;
import com.ticketing.system.ui.security.RequireCapability;
import com.ticketing.system.ui.session.AuthSession;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.radiobutton.RadioButtonGroup;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.PermitAll;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Route(value = "owner/venue/:eventId", layout = WorkspaceLayout.class)
@PageTitle("Venue Map · TicketHub")
@PermitAll
@RequireCapability(Capability.MANAGE_VENUE_MAPS)
public class VenueMapEditorView extends LkPage implements BeforeEnterObserver {

    private int gridRows = 3;
    private int gridCols = 3;
    private static final int MAX_GRID = 12;

    private final VenueMapPresenter presenter;
    private String eventId = "0";

    private record ZoneState(String name, boolean seated, int rows, int seatsPerRow,
                             int capacity, double price, GridPlacementDTO placement) {}

    private final List<ZoneState> zoneStates = new ArrayList<>();
    private int selectedZoneIdx = 0;

    private final Div mapHolder = new Div();
    private final Div zonesTableHolder = new Div();
    private final Div seatPreview = new Div();

    private final TextField zoneNameField = new TextField("Zone name");
    private final TextField priceField = new TextField("Price");
    private final RadioButtonGroup<String> zoneTypeGroup = new RadioButtonGroup<>();
    private final IntegerField rowsField = new IntegerField("Rows");
    private final IntegerField seatsPerRowField = new IntegerField("Seats / row");
    private final IntegerField capacityField = new IntegerField("Capacity");
    private final IntegerField gridRowField = new IntegerField("Grid row");
    private final IntegerField gridColField = new IntegerField("Grid col");
    private final IntegerField gridRowSpanField = new IntegerField("Row span");
    private final IntegerField gridColSpanField = new IntegerField("Col span");
    private final IntegerField gridRowsField = new IntegerField("Grid rows");
    private final IntegerField gridColsField = new IntegerField("Grid cols");
    private Div seatedEditor;
    private Div standingEditor;

    public VenueMapEditorView(VenueMapPresenter presenter) {
        this.presenter = presenter;
        zoneStates.add(new ZoneState("Zone 1", true, 10, 20, 0, 100.0, defaultPlacement(0)));
        title("Venue Map Editor");
        subtitle("Place zones on the " + gridRows + "×" + gridCols + " venue grid, then define seats.");
        actions(
            new LkBtn("Cancel").variant(LkBtn.Variant.tertiary)
                .onClick(e -> UI.getCurrent().navigate(CompanyEventListView.class)),
            new LkBtn("Save Map").variant(LkBtn.Variant.primary)
                .onClick(e -> saveMap())
        );
        add(buildSplit());
        loadZoneIntoEditor(0);
        refreshAll();
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        event.getRouteParameters().get("eventId").ifPresent(id -> this.eventId = id);
        loadZonesFromBackend();
    }

    private void loadZonesFromBackend() {
        if ("0".equals(eventId)) return;
        switch (presenter.loadZones(AuthSession.token(), Integer.parseInt(eventId))) {
            case VenueMapPresenter.LoadOutcome.Success ok -> {
                gridRows = ok.layout().gridRows();
                gridCols = ok.layout().gridCols();
                gridRowsField.setValue(gridRows);
                gridColsField.setValue(gridCols);
                if (!ok.layout().zones().isEmpty()) {
                    zoneStates.clear();
                    int idx = 0;
                    for (ZoneDetailDTO z : ok.layout().zones()) {
                        GridPlacementDTO placement = z.placement() != null ? z.placement() : defaultPlacement(idx);
                        zoneStates.add(new ZoneState(z.name(), z.seated(), z.rows(), z.seatsPerRow(),
                                z.capacity(), z.price(), placement));
                        idx++;
                    }
                    selectedZoneIdx = 0;
                    loadZoneIntoEditor(0);
                }
                refreshAll();
            }
            case VenueMapPresenter.LoadOutcome.NotAuthenticated ignored ->
                Toasts.failure("Your session has expired — please sign in again.");
            case VenueMapPresenter.LoadOutcome.Failure fail ->
                Toasts.failure("Could not load the venue map — please try again.");
        }
    }

    // ── Layout ────────────────────────────────────────────────────────────────

    private Component buildSplit() {
        Div split = new Div();
        split.addClassName("ow-venue-split");
        split.add(buildLeftCol(), buildZoneEditorCard());
        return split;
    }

    private Component buildLeftCol() {
        LkCol col = new LkCol().gap(14);
        col.add(buildCanvasCard(), buildZonesCard(), buildMapCard());
        return col;
    }

    private Component buildCanvasCard() {
        LkCard card = new LkCard("Venue Grid").pad(14);
        gridRowsField.setMin(1); gridRowsField.setMax(MAX_GRID); gridRowsField.setValue(gridRows);
        gridColsField.setMin(1); gridColsField.setMax(MAX_GRID); gridColsField.setValue(gridCols);
        gridRowsField.addValueChangeListener(e -> applyCanvasSize());
        gridColsField.addValueChangeListener(e -> applyCanvasSize());
        LkRow row = new LkRow().gap(12).align("flex-end");
        row.add(gridRowsField, gridColsField);
        card.add(row);
        Span hint = Lk.muted("Resize the canvas (saved per event); zones snap to its cells.");
        hint.getStyle().set("font-size", "12px").set("margin-top", "8px").set("display", "block");
        card.add(hint);
        return card;
    }

    private void applyCanvasSize() {
        gridRows = orDefault(gridRowsField.getValue(), gridRows);
        gridCols = orDefault(gridColsField.getValue(), gridCols);
        gridRowField.setMax(gridRows);
        gridRowSpanField.setMax(gridRows);
        gridColField.setMax(gridCols);
        gridColSpanField.setMax(gridCols);
        refreshMapHolder();
    }

    private Component buildZonesCard() {
        LkCard card = new LkCard("Zones").pad(14);
        card.add(zonesTableHolder);
        Div actions = new Div();
        actions.addClassName("ow-card-actions");
        actions.add(
            new LkBtn("+ Standing").variant(LkBtn.Variant.secondary).size(LkBtn.Size.s)
                .onClick(e -> addZone(false)),
            new LkBtn("+ Seated").variant(LkBtn.Variant.secondary).size(LkBtn.Size.s)
                .onClick(e -> addZone(true))
        );
        card.add(actions);
        return card;
    }

    private void refreshZonesTable() {
        zonesTableHolder.removeAll();
        for (int i = 0; i < zoneStates.size(); i++) {
            final int idx = i;
            ZoneState z = zoneStates.get(i);
            Div row = new Div();
            row.addClassName("ow-zone-row");
            if (idx == selectedZoneIdx) row.getStyle().set("font-weight", "600");
            row.getStyle().set("display", "flex").set("align-items", "center")
                    .set("gap", "8px").set("padding", "6px 4px").set("cursor", "pointer");
            Span name = new Span(z.name());
            name.getStyle().set("flex", "1");
            LkBadge type = new LkBadge(z.seated() ? "Seated" : "Standing",
                    z.seated() ? LkBadge.Tone.primary : LkBadge.Tone.contrast).small();
            Span cap = new Span(z.seated()
                    ? z.rows() * z.seatsPerRow() + " seats"
                    : z.capacity() + " cap");
            cap.getStyle().set("color", "var(--lk-muted, #888)").set("font-size", "13px");
            row.add(name, type, cap, removeButton(idx));
            row.getElement().addEventListener("click", e -> selectZone(idx));
            zonesTableHolder.add(row);
        }
    }

    private Component removeButton(int idx) {
        Span x = new Span("✕");
        x.getStyle().set("cursor", "pointer").set("color", "var(--lk-danger, #c0392b)")
                .set("padding", "0 4px");
        x.getElement().addEventListener("click", e -> removeZone(idx))
                .addEventData("event.stopPropagation()");
        return x;
    }

    private Component buildMapCard() {
        LkCard card = new LkCard("Map Preview").pad(14);
        card.add(mapHolder);
        Span hint = Lk.muted("Zones are positioned from their grid placement. Click a zone to edit it.");
        hint.getStyle().set("font-size", "12px").set("margin-top", "8px").set("display", "block");
        card.add(hint);
        return card;
    }

    private void refreshMapHolder() {
        mapHolder.removeAll();
        List<VkVenueMap.Zone> zones = new ArrayList<>();
        for (int i = 0; i < zoneStates.size(); i++) {
            ZoneState z = zoneStates.get(i);
            GridPlacementDTO p = z.placement();
            zones.add(new VkVenueMap.Zone(
                    "z" + i,
                    z.name(),
                    "$" + (int) z.price(),
                    i == selectedZoneIdx ? "sel" : "ok",
                    null,
                    pct((p.row() - 1) * 100.0 / gridRows),
                    pct((p.col() - 1) * 100.0 / gridCols),
                    pct(p.colSpan() * 100.0 / gridCols),
                    pct(p.rowSpan() * 100.0 / gridRows)));
        }
        mapHolder.add(new VkVenueMap(zones, "z" + selectedZoneIdx, true, this::selectZoneByVisualId));
    }

    private static String pct(double v) {
        return String.format(Locale.US, "%.2f%%", v);
    }

    private void selectZoneByVisualId(String visualId) {
        if (visualId != null && visualId.startsWith("z")) {
            try {
                selectZone(Integer.parseInt(visualId.substring(1)));
            } catch (NumberFormatException ignored) {
            }
        }
    }

    private void selectZone(int idx) {
        if (idx < 0 || idx >= zoneStates.size()) return;
        selectedZoneIdx = idx;
        loadZoneIntoEditor(idx);
        refreshZonesTable();
        refreshMapHolder();
    }

    // ── Zone editor ──────────────────────────────────────────────────────────

    private Component buildZoneEditorCard() {
        LkCard card = new LkCard("Edit Zone").pad(20);

        zoneNameField.setWidthFull();
        priceField.setPrefixComponent(new Span("$"));
        priceField.setWidthFull();

        Div formGrid = new Div();
        formGrid.getStyle().set("display", "grid").set("grid-template-columns", "1fr 1fr").set("gap", "14px");
        formGrid.add(zoneNameField, priceField);

        zoneTypeGroup.setLabel("Zone type");
        zoneTypeGroup.setItems("Seated", "Standing");
        zoneTypeGroup.getStyle().set("margin-bottom", "8px");

        seatedEditor = new Div();
        LkRow dims = new LkRow().gap(12).align("flex-end");
        rowsField.setMin(1); rowsField.setMax(99); rowsField.setWidth("120px");
        seatsPerRowField.setMin(1); seatsPerRowField.setMax(500); seatsPerRowField.setWidth("120px");
        rowsField.addValueChangeListener(e -> refreshSeatPreview());
        seatsPerRowField.addValueChangeListener(e -> refreshSeatPreview());
        dims.add(rowsField, seatsPerRowField);
        seatPreview.addClassName("ow-seat-preview");
        Span seatHint = Lk.muted("Each cell = one Seat (label + x,y). Tickets are pre-generated on save.");
        seatHint.getStyle().set("font-size", "12px").set("margin-top", "8px").set("display", "block");
        seatedEditor.add(dims, seatPreview, seatHint);

        capacityField.setMin(1); capacityField.setMax(1_000_000); capacityField.setWidthFull();
        standingEditor = new Div();
        standingEditor.add(capacityField);

        zoneTypeGroup.addValueChangeListener(ev -> {
            boolean seated = "Seated".equals(ev.getValue());
            seatedEditor.setVisible(seated);
            standingEditor.setVisible(!seated);
        });

        LkCard placementCard = new LkCard("Grid Placement (" + gridRows + "×" + gridCols + ")").pad(12);
        Div placeGrid = new Div();
        placeGrid.getStyle().set("display", "grid").set("grid-template-columns", "1fr 1fr").set("gap", "12px");
        configurePlacementField(gridRowField, gridRows);
        configurePlacementField(gridColField, gridCols);
        configurePlacementField(gridRowSpanField, gridRows);
        configurePlacementField(gridColSpanField, gridCols);
        placeGrid.add(gridRowField, gridColField, gridRowSpanField, gridColSpanField);
        placementCard.add(placeGrid);

        LkRow zoneActions = new LkRow().gap(8).justify("flex-end");
        zoneActions.getStyle().set("margin-top", "14px");
        zoneActions.add(
            new LkBtn("Revert").variant(LkBtn.Variant.secondary)
                .onClick(e -> loadZoneIntoEditor(selectedZoneIdx)),
            new LkBtn("Apply Zone").variant(LkBtn.Variant.primary)
                .onClick(e -> saveZone())
        );

        LkCol col = new LkCol().gap(14);
        col.add(formGrid, zoneTypeGroup, seatedEditor, standingEditor, placementCard, zoneActions);
        card.add(col);
        return card;
    }

    private void configurePlacementField(IntegerField field, int max) {
        field.setMin(1);
        field.setMax(max);
        field.setWidthFull();
    }

    private void loadZoneIntoEditor(int idx) {
        if (idx < 0 || idx >= zoneStates.size()) return;
        ZoneState z = zoneStates.get(idx);
        zoneNameField.setValue(z.name());
        priceField.setValue(String.valueOf((int) z.price()));
        zoneTypeGroup.setValue(z.seated() ? "Seated" : "Standing");
        rowsField.setValue(z.rows() == 0 ? 1 : z.rows());
        seatsPerRowField.setValue(z.seatsPerRow() == 0 ? 1 : z.seatsPerRow());
        capacityField.setValue(z.capacity() == 0 ? 1 : z.capacity());
        if (seatedEditor != null) seatedEditor.setVisible(z.seated());
        if (standingEditor != null) standingEditor.setVisible(!z.seated());
        GridPlacementDTO p = z.placement();
        gridRowField.setValue(p.row());
        gridColField.setValue(p.col());
        gridRowSpanField.setValue(p.rowSpan());
        gridColSpanField.setValue(p.colSpan());
        refreshSeatPreview();
    }

    private void refreshSeatPreview() {
        seatPreview.removeAll();
        int rows = Math.min(orDefault(rowsField.getValue(), 1), 8);
        int cols = Math.min(orDefault(seatsPerRowField.getValue(), 1), 16);
        String[] block = new String[Math.max(rows, 1)];
        for (int r = 0; r < block.length; r++) {
            block[r] = ".".repeat(Math.max(cols, 1));
        }
        seatPreview.add(new VkSeatBlock(block));
    }

    private void saveZone() {
        GridPlacementDTO placement = new GridPlacementDTO(
                orDefault(gridRowField.getValue(), 1),
                orDefault(gridColField.getValue(), 1),
                orDefault(gridRowSpanField.getValue(), 1),
                orDefault(gridColSpanField.getValue(), 1));
        if (placement.row() + placement.rowSpan() - 1 > gridRows
                || placement.col() + placement.colSpan() - 1 > gridCols) {
            Toasts.failure("Grid placement falls outside the " + gridRows + "×" + gridCols + " canvas.");
            return;
        }
        boolean seated = "Seated".equals(zoneTypeGroup.getValue());
        switch (presenter.validateZone(
                zoneNameField.getValue() == null ? "" : zoneNameField.getValue().trim(),
                priceField.getValue(),
                seated,
                rowsField.getValue(),
                seatsPerRowField.getValue(),
                capacityField.getValue(),
                placement)) {
            case VenueMapPresenter.ZoneOutcome.Valid v -> {
                zoneStates.set(selectedZoneIdx,
                    new ZoneState(v.name(), v.seated(), v.rows(), v.seatsPerRow(),
                            v.capacity(), v.price(), v.placement()));
                refreshAll();
                Toasts.success("Zone \"" + v.name() + "\" updated — click Save map to persist.");
            }
            case VenueMapPresenter.ZoneOutcome.InvalidName ignored ->
                Toasts.failure("Zone name is required.");
            case VenueMapPresenter.ZoneOutcome.InvalidPrice ignored ->
                Toasts.failure("Invalid price.");
        }
    }

    private void addZone(boolean seated) {
        int idx = zoneStates.size();
        ZoneState z = seated
            ? new ZoneState("Zone " + (idx + 1), true, 10, 20, 0, 100.0, defaultPlacement(idx))
            : new ZoneState("Zone " + (idx + 1), false, 0, 0, 500, 80.0, defaultPlacement(idx));
        zoneStates.add(z);
        selectedZoneIdx = idx;
        loadZoneIntoEditor(idx);
        refreshAll();
    }

    private void removeZone(int idx) {
        if (idx < 0 || idx >= zoneStates.size()) return;
        if (zoneStates.size() == 1) {
            Toasts.failure("A venue needs at least one zone.");
            return;
        }
        zoneStates.remove(idx);
        selectedZoneIdx = Math.min(selectedZoneIdx, zoneStates.size() - 1);
        loadZoneIntoEditor(selectedZoneIdx);
        refreshAll();
    }

    private void refreshAll() {
        refreshZonesTable();
        refreshMapHolder();
    }

    // ── Persist ───────────────────────────────────────────────────────────────

    private void saveMap() {
        List<VenueMapConfigDTO.ZoneConfigDTO> zoneDTOs = new ArrayList<>();
        for (ZoneState z : zoneStates) {
            if (z.seated()) {
                List<VenueMapConfigDTO.SeatConfigDTO> seats = generateSeats(z.rows(), z.seatsPerRow());
                zoneDTOs.add(new VenueMapConfigDTO.ZoneConfigDTO(
                        z.name(), true, null, seats, z.price(), z.placement()));
            } else {
                zoneDTOs.add(new VenueMapConfigDTO.ZoneConfigDTO(
                        z.name(), false, z.capacity(), null, z.price(), z.placement()));
            }
        }
        VenueMapConfigDTO config = new VenueMapConfigDTO(eventId, "Venue", gridRows, gridCols, zoneDTOs);
        switch (presenter.saveMap(AuthSession.token(), eventId, config)) {
            case VenueMapPresenter.SaveOutcome.Success ignored -> {
                Toasts.success("Venue map saved — tickets pre-generated.");
                UI.getCurrent().navigate(CompanyEventListView.class);
            }
            case VenueMapPresenter.SaveOutcome.NotAuthenticated ignored ->
                Toasts.failure("Your session has expired — please sign in again.");
            case VenueMapPresenter.SaveOutcome.NoCompany ignored ->
                Toasts.failure("You don't own a company — register one first.");
            case VenueMapPresenter.SaveOutcome.Failure fail ->
                Toasts.failure("Could not save the venue map — please try again.");
        }
    }

    private List<VenueMapConfigDTO.SeatConfigDTO> generateSeats(int rows, int seatsPerRow) {
        List<VenueMapConfigDTO.SeatConfigDTO> seats = new ArrayList<>();
        for (int r = 0; r < rows; r++) {
            String rowLabel = SeatLabels.rowLabel(r);
            for (int c = 1; c <= seatsPerRow; c++) {
                seats.add(new VenueMapConfigDTO.SeatConfigDTO(rowLabel + c, c, r));
            }
        }
        return seats;
    }

    private GridPlacementDTO defaultPlacement(int idx) {
        int row = (idx / gridCols) + 1;
        int col = (idx % gridCols) + 1;
        return new GridPlacementDTO(Math.min(row, gridRows), col, 1, 1);
    }

    private static int orDefault(Integer value, int fallback) {
        return value == null ? fallback : value;
    }
}