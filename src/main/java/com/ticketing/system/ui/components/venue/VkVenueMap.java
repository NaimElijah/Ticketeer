package com.ticketing.system.ui.components.venue;

import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.NativeButton;
import com.vaadin.flow.component.html.Span;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Schematic venue map — stage strip + clickable absolute-positioned zone
 * rectangles laid on a floor plan. Ports the React {@code VenueMap}.
 */
public class VkVenueMap extends Div {

    private static final String ARIA_PRESSED = "aria-pressed";

    /** Each zone's button, by zone id, so a page that rebuilt the map can put focus back on one. */
    private final Map<String, NativeButton> zoneButtons = new HashMap<>();

    public record Zone(String id, String label, String price, String tone,
                       String note, String top, String left, String width, String height) { }

    public VkVenueMap(List<Zone> zones, String selectedId, boolean compact, Consumer<String> onPick) {
        addClassName("vk-map");
        if (compact) addClassName("compact");

        Div stage = new Div();
        stage.addClassName("vk-stage");
        stage.setText("STAGE");
        add(stage);

        Div floor = new Div();
        floor.addClassName("vk-map-floor");
        for (Zone z : zones) {
            NativeButton zb = new NativeButton();
            zb.addClassName("vk-zone");
            zb.addClassName("vk-zone-" + z.tone);
            if (z.id.equals(selectedId)) zb.addClassName("sel");
            // The highlight shows the selected zone only to the eye (#188; SeatAndZoneStateTest).
            zb.getElement().setAttribute(ARIA_PRESSED, String.valueOf(z.id.equals(selectedId)));
            zb.getStyle()
                .set("position", "absolute")
                .set("top", z.top).set("left", z.left)
                .set("width", z.width).set("height", z.height);
            Span lbl = new Span(z.label);
            lbl.addClassName("vk-zone-label");
            Span pr = new Span(z.price);
            pr.addClassName("vk-zone-price");
            zb.add(lbl, pr);
            if (z.note != null) {
                Span note = new Span(z.note);
                note.addClassName("vk-zone-note");
                zb.add(note);
            }
            if (onPick != null && !"danger".equals(z.tone)) {
                zb.addClickListener(e -> onPick.accept(z.id));
            }
            zoneButtons.put(z.id, zb);
            floor.add(zb);
        }
        add(floor);

        Div entrances = new Div();
        entrances.addClassName("vk-map-entrances");
        Span a = new Span("◄ Gate A");
        Span b = new Span("Gate B ►");
        entrances.add(a, b);
        add(entrances);
    }

    /**
     * Focuses the button of the zone with {@code id}. A page that rebuilds the map on a pick calls it, since
     * the button the user pressed is gone with the old map (#188, WCAG 2.4.3).
     *
     * @param id the zone's id, as given in its {@link Zone}; an id not on this map does nothing
     */
    public void focusZone(String id) {
        NativeButton button = zoneButtons.get(id);
        if (button != null) {
            button.focus();
        }
    }
}
