package com.ticketing.system.ui.components.kit;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.router.RouterLink;

/**
 * Centered card shell used by sign-in and register screens. Ports the
 * React {@code AuthShell + auth-card + auth-brand + auth-title +
 * auth-sub} structure (see {@code docs/tickethub-ui/screens-auth.jsx}).
 *
 * <p>Root element is the {@code .auth-wrap} centering container; the
 * inner {@code .auth-card} holds the brand line + title + subtitle and
 * receives anything subsequently added via {@link #add(Component...)}
 * or the convenience helpers {@link #col}, {@link #divider},
 * {@link #foot}.
 */
public class LkAuthCard extends Div {

    private final Div card = new Div();

    public LkAuthCard(String title, String subtitle) {
        addClassName("auth-wrap");
        card.addClassName("auth-card");

        // Brand row
        Div brand = new Div();
        brand.addClassName("auth-brand");
        brand.add(new LkIcon("ticket", 26));
        Span name = new Span();
        name.getElement().setProperty("innerHTML", "<b>TicketHub</b>");
        brand.add(name);
        card.add(brand);

        // Title + subtitle
        H2 t = new H2(title);
        t.addClassName("auth-title");
        card.add(t);
        if (subtitle != null && !subtitle.isEmpty()) {
            Paragraph p = new Paragraph(subtitle);
            p.addClassName("auth-sub");
            card.add(p);
        }

        super.add(card);
    }

    /** Subsequent add() calls deposit into the inner card. */
    @Override
    public void add(Component... components) {
        card.add(components);
    }

    /** Group several components into a flex column with the given gap. */
    public LkAuthCard col(int gap, Component... cs) {
        Div col = new Div();
        col.getStyle()
            .set("display", "flex")
            .set("flex-direction", "column")
            .set("gap", gap + "px");
        if (cs != null) col.add(cs);
        card.add(col);
        return this;
    }

    /** "or continue with"-style divider with horizontal rule pseudo-elements. */
    public LkAuthCard divider(String label) {
        Div d = new Div();
        d.addClassName("auth-divider");
        d.add(new Span(label));
        card.add(d);
        return this;
    }

    /** Footer paragraph with optional trailing {@link RouterLink}. */
    public LkAuthCard foot(String text, String linkLabel, Class<? extends Component> linkTarget) {
        Paragraph foot = new Paragraph();
        foot.addClassName("auth-foot");
        foot.add(new Span(text + " "));
        if (linkTarget != null && linkLabel != null) {
            RouterLink link = new RouterLink(linkLabel, linkTarget);
            link.addClassName("bz-link");
            foot.add(link);
        }
        card.add(foot);
        return this;
    }
}
