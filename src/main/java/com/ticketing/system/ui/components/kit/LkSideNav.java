package com.ticketing.system.ui.components.kit;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Nav;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.router.RouterLink;

import java.util.ArrayList;
import java.util.List;

/**
 * Left drawer nav for the admin / owner shell. Heading + list of
 * icon+label items. Ports the React {@code SideNav}.
 *
 * <p>It is a {@code nav} landmark, named by its heading until {@link #label(String)}
 * names it, and the link to the page shown carries {@code aria-current="page"} as
 * well as the highlight (#188; WCAG 1.3.1, 4.1.2; {@code NavigationLandmarksTest}).
 */
public class LkSideNav extends Nav {

    public record Item(String iconName, String label, Class<? extends Component> target) {
    }

    private final Div heading = new Div();
    private final List<Div> itemEls = new ArrayList<>();
    // The link inside each item, or null for an item without a target; parallel to itemEls.
    private final List<RouterLink> itemLinks = new ArrayList<>();
    private List<Item> itemDefs = List.of();
    private String activeLabel;

    public LkSideNav(String headingText) {
        addClassName("lk-sidenav");
        heading.addClassName("lk-sidenav-h");
        heading.setText(headingText);
        setAriaLabel(headingText);
        add(heading);
    }

    /**
     * Names the navigation landmark, when its heading alone would not tell it from the
     * page's other navigation.
     *
     * @param name the landmark's accessible name
     * @return this side nav, for chaining
     */
    public LkSideNav label(String name) {
        setAriaLabel(name);
        return this;
    }

    public LkSideNav items(List<Item> items, String activeLabel) {
        this.itemDefs = items;
        for (Div el : itemEls)
            remove(el);
        itemEls.clear();
        itemLinks.clear();

        for (Item it : items) {
            Div anchor;
            RouterLink link = null;
            if (it.target != null) {
                link = new RouterLink();
                link.setRoute(it.target);
                anchor = wrapRouterLink(link, it);
            } else {
                anchor = new Div();
                buildItemContent(anchor, it);
            }
            anchor.addClassName("lk-sidenav-item");
            add(anchor);
            itemEls.add(anchor);
            itemLinks.add(link);
        }
        return setActive(activeLabel);
    }

    private Div wrapRouterLink(RouterLink link, Item it) {
        Div anchor = new Div();
        buildItemContent(link, it);
        anchor.add(link);
        link.getStyle()
                .set("display", "flex")
                .set("align-items", "center")
                .set("gap", "10px")
                .set("color", "inherit")
                .set("text-decoration", "none")
                .set("width", "100%");
        return anchor;
    }

    private void buildItemContent(Component container, Item it) {
        Span ico = new Span();
        ico.addClassName("lk-sidenav-ico");
        ico.add(new LkIcon(it.iconName, 18));
        Span lbl = new Span(it.label);
        container.getElement().appendChild(ico.getElement(), lbl.getElement());
    }

    /**
     * Toggle the {@code .on} class to highlight the item whose label
     * matches, and mark its link as the current page. Pass {@code null} to
     * clear both. Cheaper than calling {@link #items} again — no DOM rebuild.
     */
    public LkSideNav setActive(String label) {
        this.activeLabel = label;
        for (int i = 0; i < itemEls.size(); i++) {
            boolean active = itemDefs.get(i).label().equals(label);
            if (active) itemEls.get(i).addClassName("on");
            else itemEls.get(i).removeClassName("on");
            RouterLink link = itemLinks.get(i);
            if (link == null) continue;
            if (active) link.getElement().setAttribute(Lk.ARIA_CURRENT, Lk.CURRENT_PAGE);
            else link.getElement().removeAttribute(Lk.ARIA_CURRENT);
        }
        return this;
    }

    /** Tags the nav with the orange-accent admin variant so the
     *  kit's {@code .lk-admin-platform .lk-sidenav-item.on} selector
     *  paints the highlight orange instead of blue. */
    public LkSideNav platform() {
        addClassName("lk-admin-platform");
        return this;
    }
}
