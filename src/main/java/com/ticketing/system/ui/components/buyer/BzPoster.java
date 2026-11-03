package com.ticketing.system.ui.components.buyer;

import java.util.Locale;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.router.RouteParameters;
import com.vaadin.flow.router.RouterLink;

/**
 * Event poster card — gradient image-block (color-keyed to a colour family)
 * with a category chip overlay, title, venue / date meta, and price.
 * Ports the React {@code Poster} component from
 * {@code screens-buyer.jsx}. Used on the landing page's "Featured This Week"
 * and "On Sale Soon" rows.
 *
 * <p>The poster is a link to its event, named by its content, so a keyboard reaches it and a
 * screen reader reads it as one (#188; WCAG 2.1.1, 4.1.2; {@code ClickOnlyControlsTest}).
 */
public class BzPoster extends RouterLink {

    /**
     * @param gradientKey the colour family: "concert", "sport", "theatre" or "conference", in any case;
     *                    anything else gets the neutral gradient
     * @param chipLabel   the category's label, shown in capitals on the chip; kept apart from the
     *                    colour family, which several categories share (#187, UI-A6b)
     * @param title       the event's name
     * @param meta        the venue / date line
     * @param price       the price line
     */
    public BzPoster(String gradientKey, String chipLabel, String title, String meta, String price) {
        addClassName("bz-poster");

        String[] grad = switch (gradientKey.toLowerCase(Locale.ROOT)) {
            case "concert"    -> new String[]{"#8b5cf6", "#ec4899"};
            case "sport"      -> new String[]{"#0ea5e9", "#10b981"};
            case "theatre"    -> new String[]{"#dc2626", "#f59e0b"};
            case "conference" -> new String[]{"#0d9488", "#1d4ed8"};
            default           -> new String[]{"#475569", "#1a5490"};
        };

        Div img = new Div();
        img.addClassName("bz-poster-img");
        img.getStyle()
            .set("height", "150px")
            .set("background", "linear-gradient(135deg, " + grad[0] + ", " + grad[1] + ")");
        Span cat = new Span(chipLabel.toUpperCase(Locale.ROOT));
        cat.addClassName("bz-poster-cat");
        img.add(cat);

        Div body = new Div();
        body.addClassName("bz-poster-body");
        Div t = new Div(); t.addClassName("bz-poster-title"); t.setText(title);
        Div m = new Div(); m.addClassName("bz-poster-meta");  m.setText(meta);
        Div p = new Div(); p.addClassName("bz-poster-price"); p.setText(price);
        body.add(t, m, p);

        add(img, body);
    }

    /**
     * Points the poster at its event's page.
     *
     * @param target     the page that shows the event
     * @param parameters the route parameters naming the event
     * @return this poster, for chaining
     */
    public BzPoster linkTo(Class<? extends Component> target, RouteParameters parameters) {
        setRoute(target, parameters);
        return this;
    }
}
