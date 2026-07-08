package com.ticketing.system.ui.components.venue;

import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;

/**
 * Standing-zone occupancy bar — name + price + filled bar with tone +
 * "X sold · Y held · Z left" meta line. The bar fill and "left" reflect BOTH
 * sold and reserved (held) tickets, so the true free space is shown. Ports the
 * React {@code StandingZone}.
 */
public class VkStandingZone extends Div {

    public VkStandingZone(String name, int sold, int reserved, int capacity, String price) {
        addClassName("vk-standing");
        // Occupied = sold + currently-held; "left" is the true available count.
        int occupied = sold + reserved;
        int left = Math.max(0, capacity - occupied);
        int pct = capacity > 0 ? (int) Math.round(100.0 * occupied / capacity) : 0;
        String tone = pct >= 90 ? "danger" : pct >= 70 ? "warn" : "ok";

        Div head = new Div();
        head.addClassName("vk-standing-h");
        Span n = new Span();
        n.getElement().setProperty("innerHTML", "<b>" + escape(name) + "</b>");
        Span p = new Span(price);
        p.addClassName("vk-standing-price");
        head.add(n, p);

        Div bar = new Div();
        bar.addClassName("vk-standing-bar");
        Span fill = new Span();
        fill.addClassName("vk-standing-fill");
        fill.addClassName(tone);
        fill.getStyle().set("width", pct + "%");
        bar.add(fill);

        Div meta = new Div();
        meta.addClassName("vk-standing-meta");
        meta.setText(String.format("%,d sold · %,d held · %,d left", sold, reserved, left));

        add(head, bar, meta);
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
