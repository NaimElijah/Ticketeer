package com.ticketing.system.ui.components.admin;

import com.ticketing.system.ui.components.kit.Lk;
import com.vaadin.flow.component.Composite;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;

/**
 * Shared legend row for the organizational-tree views — the admin all-companies view
 * ({@code OrganizationalTreeView}) and the owner-workspace single-company view
 * ({@code CompanyOrgTreeView}). Explains the Founder / Owner / Manager node styling so
 * the two views render an identical key. Wrap it in a card (e.g. {@code new LkCard("Legend")}).
 */
public class OrgTreeLegend extends Composite<Div> {

    /** Builds the legend, one entry each for the Founder, Owner and Manager styles. */
    public OrgTreeLegend() {
        Div row = getContent();
        row.getStyle().set("display", "flex").set("gap", "20px").set("flex-wrap", "wrap");
        row.add(
            item("founder", "Founder", "Immutable — created the company."),
            item("owner",   "Owner",   "Appointed by founder or another owner."),
            item("manager", "Manager", "Appointed by an owner with granular permissions.")
        );
    }

    private static Div item(String variant, String roleLabel, String desc) {
        Div item = new Div();
        item.getStyle().set("display", "flex").set("gap", "10px").set("align-items", "center");

        // The dot shows the role's colour; the role is in the text beside it (#188, WCAG 1.1.1).
        Span dot = Lk.decoration("●");
        dot.addClassName("oc-avatar");
        dot.addClassName("oc-av-" + variant);
        dot.getStyle().set("width", "26px").set("height", "26px").set("font-size", "12px");

        Span text = new Span();
        text.getElement().setProperty("innerHTML", "<b>" + roleLabel + "</b> · " + desc);
        text.getStyle().set("font-size", "13px");

        item.add(dot, text);
        return item;
    }
}
