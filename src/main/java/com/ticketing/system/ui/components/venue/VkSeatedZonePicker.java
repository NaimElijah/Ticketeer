package com.ticketing.system.ui.components.venue;

import com.ticketing.system.Core.Application.dto.InventorySelectionDTO;
import com.vaadin.flow.component.html.Div;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * V2-RES-01 — interactive seat-by-seat picker for a SeatedZone.
 *
 * <p>Renders each seat absolutely at its (x, y) domain coordinate.
 * Colors follow the VkSeat convention: green = available, orange = selected,
 * grey = held/sold. Clicking an available seat toggles it in/out of the
 * current selection.
 *
 * <p>Read the live selection via {@link #getSelection()} and subscribe
 * to changes via the {@code onSelectionChange} callback passed at construction.
 */
public class VkSeatedZonePicker extends Div {

    // Single source of truth for the seat-tile size; MUST equal --vk-seat-size in tickethub/styles.css.
    public static final int TILE_PX = 30;

    /**
     * Presentation-layer seat descriptor — decoupled from the domain {@code Seat}
     * entity so the component works with both real service data and stub data.
     */
    public record SeatModel(String label, double x, double y, VkSeat.State initialState) {}

    private final Set<String>         selected  = new LinkedHashSet<>();
    private final Map<String, VkSeat> tiles     = new HashMap<>();
    private final Set<String>         clickable = new HashSet<>(); // only initially-free seats
    private final Runnable onSelectionChange;

    public VkSeatedZonePicker(List<SeatModel> seats, Runnable onSelectionChange) {
        this.onSelectionChange = onSelectionChange;
        addClassName("vk-seated-picker");

        Div canvas = new Div();
        canvas.addClassName("vk-picker-canvas");
        canvas.getStyle().set("position", "relative");

        double maxX = 0, maxY = 0;
        for (SeatModel seat : seats) {
            VkSeat tile = new VkSeat(seat.initialState(), seat.label());
            tile.getElement().setAttribute("title", seat.label());
            tile.getStyle()
                    .set("position", "absolute")
                    .set("left", (int) seat.x() + "px")
                    .set("top",  (int) seat.y() + "px");

            if (seat.initialState() == VkSeat.State.free) {
                tile.addClickListener(e -> toggle(seat.label(), tile));
                clickable.add(seat.label());
            }

            tiles.put(seat.label(), tile);
            canvas.add(tile);

            if (seat.x() > maxX) maxX = seat.x();
            if (seat.y() > maxY) maxY = seat.y();
        }

        // position:relative collapses without explicit dimensions — size to fit all tiles.
        canvas.getStyle()
                .set("width",  (int)(maxX + TILE_PX + 4) + "px")
                .set("height", (int)(maxY + TILE_PX + 4) + "px");

        add(canvas);
    }

    // ---------- public API ----------

    /**
     * Returns a seated {@link InventorySelectionDTO} for the labels currently
     * selected, or {@code null} when nothing is selected.
     */
    public InventorySelectionDTO getSelection() {
        if (selected.isEmpty()) return null;
        return InventorySelectionDTO.seated(new ArrayList<>(selected));
    }

    public boolean hasSelection() {
        return !selected.isEmpty();
    }

    /** Deselects a single seat by label (e.g., from the selection rail's remove button). */
    public void deselect(String label) {
        if (selected.remove(label)) {
            VkSeat tile = tiles.get(label);
            if (tile != null) tile.setState(VkSeat.State.free);
            notifyChange();
        }
    }

    /** Deselects all seats — call after a successful cart add. */
    public void clearSelection() {
        for (String label : new ArrayList<>(selected)) {
            VkSeat tile = tiles.get(label);
            if (tile != null) tile.setState(VkSeat.State.free);
        }
        selected.clear();
        notifyChange();
    }

    // ---------- internals ----------

    /** Package-private — simulates a user click on the named seat for tests.
     *  Only free-state seats are clickable; held/sold labels are a no-op. */
    void simulateClick(String label) {
        if (!clickable.contains(label)) return;
        VkSeat tile = tiles.get(label);
        if (tile != null) toggle(label, tile);
    }

    private void toggle(String label, VkSeat tile) {
        if (selected.contains(label)) {
            selected.remove(label);
            tile.setState(VkSeat.State.free);
        } else {
            selected.add(label);
            tile.setState(VkSeat.State.mine);
        }
        notifyChange();
    }

    private void notifyChange() {
        if (onSelectionChange != null) onSelectionChange.run();
    }
}
