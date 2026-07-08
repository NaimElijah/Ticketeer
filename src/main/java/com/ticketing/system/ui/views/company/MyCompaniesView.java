package com.ticketing.system.ui.views.company;

import com.ticketing.system.Core.Application.dto.UserCompanyDTO;
import com.ticketing.system.ui.presenters.company.MyCompaniesPresenter.Outcome;
import com.ticketing.system.ui.components.kit.Lk;
import com.ticketing.system.ui.components.kit.LkBadge;
import com.ticketing.system.ui.components.kit.LkBtn;
import com.ticketing.system.ui.components.kit.LkCard;
import com.ticketing.system.ui.components.kit.LkGrid;
import com.ticketing.system.ui.components.kit.LkIcon;
import com.ticketing.system.ui.components.kit.LkPage;
import com.ticketing.system.ui.components.kit.LkRow;
import com.ticketing.system.ui.components.kit.LkStatusDot;
import com.ticketing.system.ui.layouts.MainLayout;
import com.ticketing.system.ui.presenters.company.MyCompaniesPresenter;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.PermitAll;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Route(value = "my-companies", layout = MainLayout.class)
@PageTitle("My Companies · TicketHub")
@PermitAll
public class MyCompaniesView extends LkPage {

    public MyCompaniesView(MyCompaniesPresenter membershipPresenter) {
        title("My Companies");
        subtitle("Companies where you are a founder, owner, or manager.");
        actions(new LkBtn("Register a Company")
            .variant(LkBtn.Variant.primary)
            .icon(new LkIcon("plus", 15))
            .onClick(e -> UI.getCurrent().navigate(CompanyRegistrationView.class)));

        switch (membershipPresenter.load()) {
            case Outcome.Success s when s.companies().isEmpty() -> add(buildEmptyState());
            case Outcome.Success s                              -> add(buildGridCard(s.companies(), membershipPresenter));
            case Outcome.NotAuthenticated na                    -> add(buildEmptyState());
            case Outcome.Failure f -> {
                Span msg = new Span(Lk.withReason("Could not load your companies", f.reason()));
                msg.getStyle().set("display", "block").set("margin-bottom", "12px").set("color", "#b91c1c");
                add(msg);
                add(buildEmptyState());
            }
        }
    }

    private Component buildEmptyState() {
        Div empty = new Div();
        empty.getStyle()
            .set("background", "#fff")
            .set("border", "1px dashed var(--border-strong)")
            .set("border-radius", "14px")
            .set("padding", "48px 32px")
            .set("text-align", "center");

        Div iconWrap = new Div();
        iconWrap.getStyle()
            .set("width", "60px").set("height", "60px")
            .set("background", "rgba(26,84,144,0.08)")
            .set("border-radius", "12px")
            .set("color", "var(--primary)")
            .set("display", "flex").set("align-items", "center").set("justify-content", "center")
            .set("margin", "0 auto 16px");
        iconWrap.add(new LkIcon("building", 28));

        Span title = new Span("You haven't joined any companies yet");
        title.getStyle()
            .set("font-size", "1.1rem").set("font-weight", "800").set("color", "#0f172a")
            .set("display", "block");

        Span body = new Span("Register your first production company to start selling tickets, "
            + "manage events, and invite managers. You'll be its immutable founder.");
        body.getStyle()
            .set("font-size", "0.92rem").set("color", "var(--muted)")
            .set("max-width", "440px").set("line-height", "1.55")
            .set("display", "block").set("margin", "8px auto 18px");

        empty.add(iconWrap, title, body,
            new LkBtn("Register Your First Company")
                .variant(LkBtn.Variant.primary)
                .icon(new LkIcon("plus", 15))
                .onClick(e -> UI.getCurrent().navigate(CompanyRegistrationView.class)));
        return empty;
    }

    private Component buildGridCard(
            List<UserCompanyDTO> companies,
            MyCompaniesPresenter membershipPresenter) {
        LkCard card = new LkCard().pad(0);
        LkGrid grid = new LkGrid()
            .col("Company",       "company")
            .col("Role",          "role")
            .col("Status",        "status")
            .col("Members",       "members", LkGrid.Align.RIGHT)
            .col("Active events", "events",  LkGrid.Align.RIGHT)
            .col("",              "act",     LkGrid.Align.RIGHT);

        for (UserCompanyDTO company : companies) {
            Map<String, Object> row = new LinkedHashMap<>();
            Span name = new Span();
            name.getElement().setProperty("innerHTML", "<b>" + escape(company.name()) + "</b>");
            row.put("company", name);
            row.put("role", new LkBadge(company.role(), toneFor(company.role())).small());
            row.put("status", new LkStatusDot(LkStatusDot.Tone.ok, company.status()));
            row.put("members", company.members());
            row.put("events", company.activeEvents());

            LkRow actions = new LkRow().gap(6).noWrap();
            actions.add(
                new LkBtn("Open").variant(LkBtn.Variant.secondary).size(LkBtn.Size.s)
                    .onClick(e -> {
                        membershipPresenter.selectCompany(company.companyId());
                        UI.getCurrent().navigate(OwnerDashboardView.class);
                    }),
                new LkBtn("Events").variant(LkBtn.Variant.tertiary).size(LkBtn.Size.s)
                    .onClick(e -> {
                        membershipPresenter.selectCompany(company.companyId());
                        UI.getCurrent().navigate(CompanyEventListView.class);
                    })
            );
            row.put("act", actions);
            grid.row(row);
        }
        grid.build();
        card.add(grid);
        return card;
    }

    private static LkBadge.Tone toneFor(String role) {
        return switch (role) {
            case "Founder"  -> LkBadge.Tone.warning;
            case "Co-owner" -> LkBadge.Tone.primary;
            default         -> LkBadge.Tone.success;
        };
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
