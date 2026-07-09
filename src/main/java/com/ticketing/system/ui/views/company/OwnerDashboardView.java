package com.ticketing.system.ui.views.company;

import com.ticketing.system.shared.dto.CompanyDashboardDTO;
import com.ticketing.system.shared.dto.MyCompanyDTO;
import com.ticketing.system.ui.components.kit.Lk;
import com.ticketing.system.ui.components.kit.LkBanner;
import com.ticketing.system.ui.components.kit.LkBtn;
import com.ticketing.system.ui.components.kit.LkIcon;
import com.ticketing.system.ui.components.kit.LkPage;
import com.ticketing.system.ui.components.kit.LkSelect;
import com.ticketing.system.ui.components.kit.LkStat;
import com.ticketing.system.ui.components.kit.LkTile;
import com.ticketing.system.ui.layouts.WorkspaceLayout;
import com.ticketing.system.ui.presenters.company.OwnerDashboardPresenter;
import com.ticketing.system.ui.security.Capabilities;
import com.ticketing.system.ui.security.Capability;
import com.ticketing.system.ui.security.RequireCapability;
import com.ticketing.system.ui.session.AuthSession;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.PermitAll;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Route(value = "owner", layout = WorkspaceLayout.class)
@PageTitle("Workspace · TicketHub")
@PermitAll
@RequireCapability(Capability.OWNER_WORKSPACE)
public class OwnerDashboardView extends LkPage {

    private final OwnerDashboardPresenter presenter;

    /** Stat tiles live in their own slot so the company selector can rebuild them in place. */
    private final Div statsSlot = new Div();

    public OwnerDashboardView(OwnerDashboardPresenter presenter) {
        this.presenter = presenter;

        title("Workspace");
        add(statsSlot);
        add(Lk.h2("Manage"));
        add(buildTiles());
        reload(null);
    }

    /** (Re)loads the dashboard for the given company (null → the member's first company). */
    private void reload(Integer companyId) {
        switch (presenter.loadFor(AuthSession.token(), companyId)) {
            case OwnerDashboardPresenter.Outcome.Success ok -> applySuccess(ok);
            case OwnerDashboardPresenter.Outcome.NoCompany ignored -> showBanner(
                "You don't belong to a production company yet. Register one to open its workspace.");
            case OwnerDashboardPresenter.Outcome.NotAuthenticated ignored -> showBanner(
                "Your session has expired — please sign in again.");
            case OwnerDashboardPresenter.Outcome.Failure fail -> showBanner(
                Lk.withReason("Could not load your workspace", fail.reason()));
        }
    }

    private void applySuccess(OwnerDashboardPresenter.Outcome.Success ok) {
        MyCompanyDTO selected = ok.selected();
        subtitle(selected.name() + "  ·  you are the " + selected.role());
        actions(buildActions(ok.companies(), selected));
        statsSlot.removeAll();
        statsSlot.add(buildStats(ok.stats()));
    }

    private void showBanner(String message) {
        subtitle("");
        actions();
        statsSlot.removeAll();
        statsSlot.add(new LkBanner(LkBanner.Tone.info, new LkIcon("info", 18), message));
    }

    /** Topbar actions: a company selector (only when the member has >1) plus "New event". */
    private Component[] buildActions(List<MyCompanyDTO> companies, MyCompanyDTO selected) {
        LkBtn newEvent = new LkBtn("New Event")
            .variant(LkBtn.Variant.primary)
            .icon(new LkIcon("plus", 15))
            .onClick(e -> UI.getCurrent().navigate(CompanyEventListView.class));

        if (companies.size() <= 1) {
            return new Component[] { newEvent };
        }

        // Two companies can share a display name, so key the selector on a label
        // that maps unambiguously back to a single companyId (disambiguating
        // collisions with the id) — otherwise switching could reload the wrong stats.
        List<String> labels = new ArrayList<>();
        Map<String, Integer> idByLabel = new LinkedHashMap<>();
        String selectedLabel = selected.name();
        for (MyCompanyDTO c : companies) {
            String label = idByLabel.containsKey(c.name()) ? c.name() + " · #" + c.companyId() : c.name();
            labels.add(label);
            idByLabel.put(label, c.companyId());
            if (c.companyId() == selected.companyId()) {
                selectedLabel = label;
            }
        }
        LkSelect selector = new LkSelect(selectedLabel, labels).label("Company");
        selector.onChange(label -> {
            Integer companyId = idByLabel.get(label);
            if (companyId != null) {
                reload(companyId);
            }
        });
        return new Component[] { selector, newEvent };
    }

    private Component buildStats(CompanyDashboardDTO stats) {
        Div row = new Div();
        row.addClassName("ow-stats");

        // Company rating is derived from the average of this company's events' ratings.
        LkStat rating = new LkStat("Rating",
            stats.rating() == null ? "—" : "★ " + ratingText(stats.rating()));

        row.add(
            rating,
            new LkStat("Live events",        String.valueOf(stats.activeEvents())),
            new LkStat("Tickets sold · 30d",  String.format("%,d", stats.ticketsSold30d()))
        );
        // Revenue is sales data — only members with VIEW_SALES (VIEW_COMPANY_SALES) see it, matching
        // the "Sales History" tile gate below and the VIEW_COMPANY_SALES-gated sales page.
        if (Capabilities.has(Capability.VIEW_COMPANY_SALES)) {
            row.add(new LkStat("Revenue · 30d", "$" + String.format("%,.0f", stats.revenue30d())));
        }
        // Open inquiries is inquiry-handling data — only members with RESPOND_TO_INQUIRIES
        // (RESPOND_INQUIRIES) see it, matching the "Customer Inquiries" tile gate below.
        if (Capabilities.has(Capability.RESPOND_INQUIRIES)) {
            LkStat inquiries = new LkStat("Open inquiries", String.valueOf(stats.openInquiries()));
            if (stats.openInquiries() > 0) {
                inquiries.delta("needs reply", LkStat.Tone.warn);
            }
            row.add(inquiries);
        }
        return row;
    }

    /** Rating shown without a trailing ".0" (e.g. 4.5 → "4.5", 4.0 → "4"). */
    private static String ratingText(double rating) {
        return rating == Math.floor(rating) ? String.valueOf((int) rating) : String.valueOf(rating);
    }

    /**
     * Build the tile grid, dropping any tile whose target view the user
     * doesn't have access to. Without this filter, a manager clicking
     * (say) "Managers" would just bounce off the capability gate and
     * land back here — the dead-click UX problem.
     */
    private Component buildTiles() {
        Div tiles = new Div();
        tiles.addClassName("ow-tiles");

        if (Capabilities.has(Capability.VIEW_COMPANY_EVENTS))
            tiles.add(tile("calendar", "My Events",
                "Edit metadata, venue map, policies, or cancel an event.",
                CompanyEventListView.class));

        if (Capabilities.has(Capability.RESPOND_INQUIRIES))
            tiles.add(tile("comment", "Customer Inquiries",
                "View member inquiries (with their username) and respond in a chat thread.",
                CompanyInquiryInboxView.class));

        if (Capabilities.has(Capability.VIEW_COMPANY_SALES))
            tiles.add(tile("chart", "Sales History",
                "Per-company sales with date / event filters. Immutable receipts.",
                CompanySalesView.class));

        if (Capabilities.has(Capability.APPOINT_MANAGER))
            tiles.add(tile("users", "Managers",
                "Active managers + pending invites. Edit permissions or revoke.",
                ManagerListView.class));

        if (Capabilities.has(Capability.APPOINT_CO_OWNER))
            tiles.add(tile("crown", "Appoint Co-owner",
                "Invite another member as co-owner. Cycle-prevention enforced.",
                OwnerAppointmentView.class));

        if (Capabilities.has(Capability.VIEW_COMPANY_ORG_TREE))
            tiles.add(tile("org", "Organizational Tree",
                "View your company's founder → owners → managers appointment hierarchy.",
                CompanyOrgTreeView.class));

        if (Capabilities.has(Capability.EDIT_PURCHASE_POLICIES))
            tiles.add(tile("policy", "Purchase Policies",
                "Visual AND/OR builder for company- or event-level rules.",
                PurchasePolicyEditorView.class));

        // "Register new company" is universal — any signed-in user can start
        // a new company and become its founder.
        if (Capabilities.has(Capability.REGISTER_COMPANY))
            tiles.add(tile("briefcase", "Register New Company",
                "Found another production company. You become the founder.",
                CompanyRegistrationView.class));

        return tiles;
    }

    private LkTile tile(String iconName, String title, String desc, Class<? extends Component> target) {
        LkTile t = new LkTile(new LkIcon(iconName, 26), title, desc);
        t.addClickListener(e -> UI.getCurrent().navigate(target));
        return t;
    }
}
