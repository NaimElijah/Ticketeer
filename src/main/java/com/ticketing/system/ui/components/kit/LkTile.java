package com.ticketing.system.ui.components.kit;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.router.RouterLink;

/**
 * Dashboard navigation tile — large clickable card with icon, title,
 * description, and a blue "Open →" affordance. Ports the React
 * {@code Tile} component.
 *
 * <p>The tile is a link to the page it opens, named by its title and description; the
 * "Open →" is decoration, hidden from screen readers (#188; WCAG 2.1.1, 2.5.3, 4.1.2;
 * {@code ClickOnlyControlsTest}).
 */
public class LkTile extends RouterLink {

    private static final String OPEN = "Open →";

    private final Span iconSlot = new Span();
    private final Span titleSpan = new Span();
    private final Span descSpan = new Span();
    private final Span goSpan = new Span(OPEN);

    /**
     * @param icon        the tile's icon, or {@code null} for none
     * @param title       the tile's title
     * @param description one line on what the page does
     * @param target      the page the tile opens
     */
    public LkTile(Component icon, String title, String description, Class<? extends Component> target) {
        setRoute(target);
        addClassName("lk-tile");
        iconSlot.addClassName("lk-tile-icon");
        titleSpan.addClassName("lk-tile-title");
        descSpan.addClassName("lk-tile-desc");
        goSpan.addClassName("lk-tile-go");
        goSpan.getElement().setAttribute(Lk.ARIA_HIDDEN, "true");
        if (icon != null) iconSlot.add(icon);
        titleSpan.setText(title);
        descSpan.setText(description);
        add(iconSlot, titleSpan, descSpan, goSpan);
    }

    public LkTile active() { addClassName("on"); return this; }
}
