package com.ticketing.system.ui.components.kit;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.NativeButton;
import com.vaadin.flow.component.html.Span;

/**
 * Dashboard navigation tile — large clickable card with icon, title,
 * description, and a blue "Open →" affordance. Ports the React
 * {@code Tile} component.
 */
public class LkTile extends NativeButton {

    private final Span iconSlot = new Span();
    private final Span titleSpan = new Span();
    private final Span descSpan = new Span();
    private final Span goSpan = new Span("Open →");

    public LkTile(Component icon, String title, String description) {
        addClassName("lk-tile");
        iconSlot.addClassName("lk-tile-icon");
        titleSpan.addClassName("lk-tile-title");
        descSpan.addClassName("lk-tile-desc");
        goSpan.addClassName("lk-tile-go");
        if (icon != null) iconSlot.add(icon);
        titleSpan.setText(title);
        descSpan.setText(description);
        add(iconSlot, titleSpan, descSpan, goSpan);
    }

    public LkTile active() { addClassName("on"); return this; }
}
