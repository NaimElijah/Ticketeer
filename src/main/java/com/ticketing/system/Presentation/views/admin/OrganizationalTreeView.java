package com.ticketing.system.Presentation.views.admin;
import com.ticketing.system.identity.domain.Admin;

import com.ticketing.system.Core.Application.dto.OrganizationalTreeNodeDTO;
import com.ticketing.system.Core.Application.dto.ProductionCompanyDTO;
import com.ticketing.system.Presentation.components.admin.OrgTreeLegend;
import com.ticketing.system.Presentation.components.admin.OrgTreeRenderer;
import com.ticketing.system.Presentation.components.kit.Lk;
import com.ticketing.system.Presentation.components.kit.LkBanner;
import com.ticketing.system.Presentation.components.kit.LkCard;
import com.ticketing.system.Presentation.components.kit.LkIcon;
import com.ticketing.system.Presentation.components.kit.LkPage;
import com.ticketing.system.Presentation.layouts.PlatformAdminLayout;
import com.ticketing.system.Presentation.presenters.admin.OrgTreePresenter;
import com.ticketing.system.Presentation.security.Capability;
import com.ticketing.system.Presentation.security.RequireCapability;
import com.ticketing.system.Presentation.session.AuthSession;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.PermitAll;

import java.util.List;

@Route(value = "admin/org-tree", layout = PlatformAdminLayout.class)
@PageTitle("Organizational Tree · Admin")
@PermitAll
@RequireCapability(Capability.VIEW_ORG_TREES)
public class OrganizationalTreeView extends LkPage {

    private final OrgTreePresenter presenter;
    private final Div body = new Div();
    private Integer selectedCompanyId = null;

    public OrganizationalTreeView(OrgTreePresenter presenter) {
        this.presenter = presenter;
        title("Organizational Tree");
        subtitle("Pick a company on the left to see its founder → owners → managers hierarchy.");
        add(body);
        render();
    }

    private void render() {
        body.removeAll();

        switch (presenter.load(AuthSession.token(), selectedCompanyId, AuthSession.isAdmin())) {
            case OrgTreePresenter.Outcome.NotAuthenticated ignored ->
                body.add(new LkBanner(LkBanner.Tone.warn, new LkIcon("lock", 16),
                    "Your session has expired — please sign in again."));
            case OrgTreePresenter.Outcome.NoCompany ignored ->
                body.add(new LkBanner(LkBanner.Tone.info, new LkIcon("info", 16),
                    AuthSession.isAdmin()
                        ? "No companies exist in the system yet."
                        : "You have no owned companies."));
            case OrgTreePresenter.Outcome.Failure fail ->
                body.add(new LkBanner(LkBanner.Tone.error, new LkIcon("warning", 16),
                     Lk.withReason("Could not load the tree", fail.reason())));
            case OrgTreePresenter.Outcome.Success ok ->
                body.add(buildMasterDetail(ok.companies(), ok.selected(), ok.tree()));
        }
    }

    /** Two-column master/detail: all companies on the left, the selected company's tree on the right. */
    private Component buildMasterDetail(List<ProductionCompanyDTO> companies,
                                        ProductionCompanyDTO selected,
                                        OrganizationalTreeNodeDTO tree) {
        Div split = new Div();
        split.getStyle().set("display", "flex").set("gap", "16px")
            .set("align-items", "flex-start").set("flex-wrap", "wrap");

        Div left = new Div();
        left.getStyle().set("flex", "0 0 280px").set("min-width", "240px");
        left.add(buildCompanyList(companies, selected));

        Div right = new Div();
        right.getStyle().set("flex", "1 1 480px").set("min-width", "320px")
            .set("display", "flex").set("flex-direction", "column").set("gap", "16px");
        right.add(buildTreeCard(selected.name(), tree), buildLegendCard());

        split.add(left, right);
        return split;
    }

    private Component buildCompanyList(List<ProductionCompanyDTO> companies, ProductionCompanyDTO selected) {
        LkCard card = new LkCard("Companies (" + companies.size() + ")").pad(12);

        Div list = new Div();
        list.getStyle().set("display", "flex").set("flex-direction", "column").set("gap", "8px")
            .set("max-height", "70vh").set("overflow", "auto");
        for (ProductionCompanyDTO c : companies) {
            list.add(companyItem(c, c.companyId() == selected.companyId()));
        }
        card.add(list);
        return card;
    }

    private Component companyItem(ProductionCompanyDTO c, boolean active) {
        Div item = new Div();
        item.getStyle()
            .set("display", "flex").set("flex-direction", "column").set("gap", "2px")
            .set("padding", "10px 12px").set("border-radius", "8px").set("cursor", "pointer")
            .set("border", "1px solid " + (active ? "#0f172a" : "#e2e8f0"))
            .set("background", active ? "#0f172a" : "#fff");

        Span name = new Span(c.name());
        name.getStyle().set("font-weight", "700").set("font-size", "13.5px")
            .set("color", active ? "#fff" : "#1e293b");

        Span meta = new Span(c.status());
        meta.getStyle().set("font-size", "12px")
            .set("color", active ? "#cbd5e1" : "#94a3b8");

        item.add(name, meta);
        item.addClickListener(e -> {
            selectedCompanyId = c.companyId();
            render();
        });
        return item;
    }

    private Component buildTreeCard(String companyName, OrganizationalTreeNodeDTO root) {
        LkCard card = new LkCard(companyName + " — Appointment Hierarchy").pad(20);
        card.add(new OrgTreeRenderer(root));
        return card;
    }

    private Component buildLegendCard() {
        LkCard card = new LkCard("Legend").pad(16);
        card.add(new OrgTreeLegend());
        return card;
    }
}
