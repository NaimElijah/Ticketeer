package com.ticketing.system.ui.views.admin;
import com.ticketing.system.identity.domain.Admin;

import com.ticketing.system.Core.Application.dto.AdminOverviewDTO;
import com.ticketing.system.ui.components.kit.LkCard;
import com.ticketing.system.ui.components.kit.LkIcon;
import com.ticketing.system.ui.components.kit.LkPage;
import com.ticketing.system.ui.components.kit.LkStat;
import com.ticketing.system.ui.components.kit.LkTile;
import com.ticketing.system.ui.layouts.PlatformAdminLayout;
import com.ticketing.system.ui.presenters.admin.AdminDashboardPresenter;
import com.ticketing.system.ui.security.Capability;
import com.ticketing.system.ui.security.RequireCapability;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.PermitAll;

@Route(value = "admin", layout = PlatformAdminLayout.class)
@PageTitle("Admin Workspace · Event Ticket Platform")
@PermitAll
@RequireCapability(Capability.ADMIN_WORKSPACE)
public class AdminDashboardView extends LkPage {

    private final AdminDashboardPresenter presenter;

    public AdminDashboardView(AdminDashboardPresenter presenter) {
        this.presenter = presenter;

        title("Admin Workspace");
        subtitle("Cross-company moderation, broadcasts, and platform analytics.");

        add(buildStats());
        add(buildTiles());
    }

    private Component buildStats() {
        Div row = new Div();
        row.addClassName("ow-stats");
        switch (presenter.load()) {
            case AdminDashboardPresenter.Outcome.Success ok -> {
                AdminOverviewDTO o = ok.overview();
                LkStat complaints = new LkStat("Open complaints", String.format("%,d", o.openComplaints()));
                if (o.openComplaints() > 0) {
                    complaints.delta("needs attention", LkStat.Tone.warn);
                }
                row.add(
                    new LkStat("Active companies",        String.format("%,d", o.activeCompanies())),
                    new LkStat("Live events",             String.format("%,d", o.liveEvents())),
                    complaints,
                    new LkStat("Platform revenue · 30d",  "$" + String.format("%,.0f", o.revenue30d()))
                );
            }
            case AdminDashboardPresenter.Outcome.Failure ignored -> row.add(
                new LkStat("Active companies",        "—"),
                new LkStat("Live events",             "—"),
                new LkStat("Open complaints",         "—"),
                new LkStat("Platform revenue · 30d",  "—")
            );
        }
        return row;
    }

    private Component buildTiles() {
        Div tiles = new Div();
        tiles.addClassName("ow-tiles");
        tiles.add(
            tile("chart",   "Global purchase history",
                "Every order across the platform with filters and revenue aggregations.",
                GlobalHistoryView.class),
            tile("org",     "Organizational tree",
                "Per-company founder → owners → managers hierarchy.",
                OrganizationalTreeView.class),
            tile("comment", "Send Messages",
                "Message individual members, all members, or all producers.",
                AdminSendMessagesView.class),
            tile("warning", "Complaint queue",
                "Member complaints opened via Conversation type COMPLAINT.",
                AdminComplaintQueueView.class)
        );

        LkCard card = new LkCard("Admin Tools").pad(20);
        card.add(tiles);
        return card;
    }

    private LkTile tile(String iconName, String title, String desc, Class<? extends Component> target) {
        LkTile t = new LkTile(new LkIcon(iconName, 26), title, desc);
        t.addClickListener(e -> UI.getCurrent().navigate(target));
        return t;
    }
}
