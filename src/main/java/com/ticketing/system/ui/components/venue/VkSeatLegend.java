package com.ticketing.system.ui.components.venue;

import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;

/**
 * Legend bar explaining the four seat states. Ports the React
 * {@code SeatLegend}.
 */
public class VkSeatLegend extends Div {

    public VkSeatLegend() {
        addClassName("vk-legend");
        item(VkSeat.State.free, "Available");
        item(VkSeat.State.mine, "In your order");
        item(VkSeat.State.held, "Locked by others");
        item(VkSeat.State.sold, "Sold");
    }

    private void item(VkSeat.State s, String label) {
        Span row = new Span();
        row.addClassName("vk-legend-item");
        Span swatch = new Span();
        swatch.addClassName("vk-seat");
        swatch.addClassName("vk-seat-" + s.name());
        swatch.addClassName("vk-legend-swatch");
        Span lbl = new Span(label);
        row.add(swatch, lbl);
        add(row);
    }
}
