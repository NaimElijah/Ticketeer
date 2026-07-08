package com.ticketing.system.Presentation.components.venue;

import com.ticketing.system.catalog.domain.SeatLabels;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;

/**
 * Rows of seats. Each input row is a string of state codes:
 * <ul>
 *   <li>{@code .} — free</li>
 *   <li>{@code m} — mine (in your order)</li>
 *   <li>{@code h} — held by other buyer</li>
 *   <li>{@code x} — sold</li>
 * </ul>
 * Ports the React {@code SeatBlock}.
 */
public class VkSeatBlock extends Div {

    public VkSeatBlock(String[] rows) { this(rows, null, 'A'); }
    public VkSeatBlock(String[] rows, String label, char startRowChar) {
        addClassName("vk-seatblock");
        if (label != null) {
            Div lbl = new Div();
            lbl.addClassName("vk-seatblock-label");
            lbl.setText(label);
            add(lbl);
        }
        for (int ri = 0; ri < rows.length; ri++) {
            Div row = new Div();
            row.addClassName("vk-seatrow");
            Span rl = new Span(SeatLabels.rowLabel((startRowChar - 'A') + ri));
            rl.addClassName("vk-rowlabel");
            row.add(rl);
            String r = rows[ri];
            for (int ci = 0; ci < r.length(); ci++) {
                VkSeat.State state = switch (r.charAt(ci)) {
                    case 'm' -> VkSeat.State.mine;
                    case 'h' -> VkSeat.State.held;
                    case 'x' -> VkSeat.State.sold;
                    default  -> VkSeat.State.free;
                };
                row.add(new VkSeat(state, String.valueOf(ci + 1)));
            }
            add(row);
        }
    }
}
