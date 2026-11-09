package com.ticketing.system.ui.components.kit;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.Div;

/**
 * Horizontal flex row. Ports the React {@code Row} primitive.
 */
public class LkRow extends Div {

    /** A row of {@code children}, 12 px apart, centred vertically, wrapping when full; none leaves it empty. */
    public LkRow(Component... children) {
        getStyle()
                .set("display", "flex")
                .set("flex-wrap", "wrap")
                .set("align-items", "center")
                .set("gap", "12px");
        if (children != null && children.length > 0)
            add(children);
    }

    /** Sets the space between the children, in pixels. */
    public LkRow gap(int px) {
        getStyle().set("gap", px + "px");
        return this;
    }

    /** Keeps the children on one line instead of wrapping them. */
    public LkRow noWrap() {
        getStyle().set("flex-wrap", "nowrap");
        return this;
    }

    /** Sets the CSS {@code align-items} value, such as {@code "flex-start"}. */
    public LkRow align(String value) {
        getStyle().set("align-items", value);
        return this;
    }

    /** Sets the CSS {@code justify-content} value, such as {@code "space-between"}. */
    public LkRow justify(String value) {
        getStyle().set("justify-content", value);
        return this;
    }
}
