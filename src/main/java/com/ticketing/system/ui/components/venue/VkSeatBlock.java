package com.ticketing.system.ui.components.venue;

import com.ticketing.system.catalog.domain.SeatLabels;
import com.ticketing.system.ui.components.kit.Lk;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;

import java.util.ArrayList;
import java.util.List;

/**
 * Rows of seats. Each input row is a string of state codes:
 * <ul>
 *   <li>{@code .} — free</li>
 *   <li>{@code m} — mine (in your order)</li>
 *   <li>{@code h} — held (reserved, not yet paid for)</li>
 *   <li>{@code x} — sold</li>
 * </ul>
 * Ports the React {@code SeatBlock}.
 *
 * <p>{@link #asPreview(String)} turns the block into a picture: its seats leave the Tab order
 * and screen readers, and one line of text says what it shows (#188; WCAG 2.4.3, 4.1.2;
 * {@code SeatAndZoneStateTest}).
 */
public class VkSeatBlock extends Div {

    private final List<VkSeat> seats = new ArrayList<>();

    /**
     * A block without a heading, its first row labelled "A".
     *
     * @param rows one string per row, one state code per seat
     * @see #VkSeatBlock(String[], String, char)
     */
    public VkSeatBlock(String[] rows) { this(rows, null, 'A'); }

    /**
     * @param rows         one string per row, one state code per seat in column order; any character
     *                     other than the codes above reads as free. Seats are numbered from 1 in each row.
     * @param label        a heading above the rows; {@code null} for none
     * @param startRowChar the letter of the first row's label, {@code 'A'} or later; later rows
     *                     continue the sequence ({@code Z} is followed by {@code AA})
     * @throws IllegalArgumentException if there is a row and {@code startRowChar} is below {@code 'A'}
     */
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
                VkSeat seat = new VkSeat(state, String.valueOf(ci + 1));
                seats.add(seat);
                row.add(seat);
            }
            add(row);
        }
    }

    /**
     * Makes the block a picture of seats nobody picks, such as the venue editor's preview: each
     * seat is {@link VkSeat#decorative() decorative}, and {@code summary} is read instead.
     *
     * @param summary what the picture shows, e.g. "8 rows of 16 seats"; visually hidden
     * @return this block, for chaining
     */
    public VkSeatBlock asPreview(String summary) {
        seats.forEach(VkSeat::decorative);
        Span text = new Span(summary);
        text.addClassName(Lk.VISUALLY_HIDDEN);
        add(text);
        return this;
    }
}
