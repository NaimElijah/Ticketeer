package com.ticketing.system.ui.views.company;
import com.ticketing.system.organization.application.service.CompanyManagementService;

import com.ticketing.system.organization.application.dto.OrganizationalTreeNodeDTO;
import com.ticketing.system.ui.components.admin.OrgTreeLegend;
import com.ticketing.system.ui.components.admin.OrgTreeRenderer;
import com.ticketing.system.ui.components.kit.Lk;
import com.ticketing.system.ui.components.kit.LkBanner;
import com.ticketing.system.ui.components.kit.LkCard;
import com.ticketing.system.ui.components.kit.LkIcon;
import com.ticketing.system.ui.components.kit.LkPage;
import com.ticketing.system.ui.layouts.WorkspaceLayout;
import com.ticketing.system.ui.presenters.admin.OrgTreePresenter;
import com.ticketing.system.ui.security.Capability;
import com.ticketing.system.ui.security.RequireCapability;
import com.ticketing.system.ui.session.AuthSession;
import com.ticketing.system.ui.session.CurrentCompanies;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.PermitAll;

/**
 * Owner-workspace view of the current company's organizational tree (founder → owners →
 * managers). Reuses {@link OrgTreePresenter}'s owner path — scoped to the company in focus
 * ({@link CurrentCompanies}), falling back to the caller's first owned company — and the shared
 * {@link OrgTreeRenderer} / {@link OrgTreeLegend}. The admin all-companies variant lives in
 * {@code OrganizationalTreeView}; a Vaadin view binds to one layout, so the two shells need
 * separate view classes. Gated by {@link Capability#VIEW_COMPANY_ORG_TREE} (Founder + Co-owner;
 * managers cannot view it, matching {@code CompanyManagementService.viewOrganizationalTree}).
 */
@Route(value = "owner/org-tree", layout = WorkspaceLayout.class)
@PageTitle("Organizational Tree · Workspace")
@PermitAll
@RequireCapability(Capability.VIEW_COMPANY_ORG_TREE)
public class CompanyOrgTreeView extends LkPage {

    private final OrgTreePresenter presenter;
    private final Div body = new Div();

    public CompanyOrgTreeView(OrgTreePresenter presenter) {
        this.presenter = presenter;
        title("Organizational Tree");
        subtitle("Your company's founder → owners → managers hierarchy.");
        add(body);
        render();
    }

    private void render() {
        body.removeAll();

        // Owner path (isAdmin = false): scoped to the workspace's current company, with the
        // presenter falling back to the first owned company when none is selected.
        switch (presenter.load(AuthSession.token(), CurrentCompanies.currentCompanyId(), false)) {
            case OrgTreePresenter.Outcome.NotAuthenticated ignored ->
                body.add(new LkBanner(LkBanner.Tone.warn, new LkIcon("lock", 16),
                    "Your session has expired — please sign in again."));
            case OrgTreePresenter.Outcome.NoCompany ignored ->
                body.add(new LkBanner(LkBanner.Tone.info, new LkIcon("info", 16),
                    "You have no owned companies yet."));
            case OrgTreePresenter.Outcome.Failure fail ->
                body.add(new LkBanner(LkBanner.Tone.error, new LkIcon("warning", 16),
                     Lk.withReason("Could not load the tree", fail.reason())));
            case OrgTreePresenter.Outcome.Success ok -> {
                body.add(buildTreeCard(ok.selected().name(), ok.tree()));
                body.add(buildLegendCard());
            }
        }
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
