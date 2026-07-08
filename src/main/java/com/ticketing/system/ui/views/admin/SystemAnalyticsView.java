package com.ticketing.system.ui.views.admin;
import com.ticketing.system.identity.domain.Admin;

import com.ticketing.system.Core.Application.dto.MarketStateDTO;
import com.ticketing.system.Core.Application.dto.SystemAnalyticsDTO;
import com.ticketing.system.ui.components.Toasts;
import com.ticketing.system.ui.components.kit.Lk;
import com.ticketing.system.ui.components.kit.LkBadge;
import com.ticketing.system.ui.components.kit.LkBanner;
import com.ticketing.system.ui.components.kit.LkBtn;
import com.ticketing.system.ui.components.kit.LkCard;
import com.ticketing.system.ui.components.kit.LkIcon;
import com.ticketing.system.ui.components.kit.LkPage;
import com.ticketing.system.ui.components.kit.LkStat;
import com.ticketing.system.ui.layouts.PlatformAdminLayout;
import com.ticketing.system.ui.presenters.admin.SystemAnalyticsPresenter;
import com.ticketing.system.ui.security.Capability;
import com.ticketing.system.ui.security.RequireCapability;
import com.ticketing.system.ui.session.AuthSession;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.shared.Registration;
import jakarta.annotation.security.PermitAll;

/**
 * System Analytics dashboard (UC-46 / #43, #279). Admin-only page reached from
 * the "Analytics" link in the platform-admin top bar. Shows the trading-market
 * status with Open / Close controls, plus live platform performance metrics
 * (visitor entry/exit, registrations, reservation/purchase rate and throughput).
 *
 * <p>View → Presenter → Service: the view renders by switching on the
 * presenter's sealed outcomes and never calls services directly. The KPI numbers
 * refresh on open, on the manual "Refresh" button, and on a poll tick
 * ({@link SystemAnalyticsPresenter#refreshIntervalMs()}); the Open/Close buttons
 * are built once so a refresh never drops a click.
 */
@Route(value = "admin/analytics", layout = PlatformAdminLayout.class)
@PageTitle("System Analytics · Event Ticket Platform")
@PermitAll
@RequireCapability(Capability.ADMIN_WORKSPACE)
public class SystemAnalyticsView extends LkPage {

    private final SystemAnalyticsPresenter presenter;

    private final LkBadge statusBadge = new LkBadge("—");
    private final Span marketDetail = new Span();
    private final Div statsSlot = new Div();

    private Registration pollRegistration;

    public SystemAnalyticsView(SystemAnalyticsPresenter presenter) {
        this.presenter = presenter;

        title("System Analytics");
        subtitle("Real-time and historical platform performance metrics.");
        actions(
            new LkBtn("Refresh").variant(LkBtn.Variant.secondary).onClick(e -> refreshData()),
            new LkBtn("Back").variant(LkBtn.Variant.tertiary)
                .icon(new LkIcon("arrowLeft", 15))
                .onClick(e -> UI.getCurrent().navigate(AdminDashboardView.class))
        );

        add(buildMarketCard());
        add(statsSlot);

        refreshData();
    }

    // Built once so the Open/Close buttons survive every refresh/poll (no click races).
    private Component buildMarketCard() {
        LkBtn openBtn = new LkBtn("Open Market").variant(LkBtn.Variant.success)
            .onClick(e -> doMarket(true));
        LkBtn closeBtn = new LkBtn("Close Market").variant(LkBtn.Variant.error)
            .onClick(e -> doMarket(false));

        Div controls = new Div(statusBadge, openBtn, closeBtn);
        controls.getStyle().set("display", "flex").set("align-items", "center").set("gap", "12px");

        marketDetail.getStyle().set("display", "block").set("margin-top", "10px")
            .set("color", "var(--muted, #6b7280)").set("font-size", "13px");

        LkCard card = new LkCard("Market Status").pad(20);
        card.add(controls, marketDetail);
        return card;
    }

    private void doMarket(boolean open) {
        String token = AuthSession.token();
        SystemAnalyticsPresenter.MarketActionOutcome outcome = open
            ? presenter.openMarket(token, "admin dashboard")
            : presenter.closeMarket(token, "admin dashboard");

        switch (outcome) {
            case SystemAnalyticsPresenter.MarketActionOutcome.Success ok -> {
                Toasts.success(open ? "Market opened." : "Market closed.");
                applyStatus(ok.market());
            }
            case SystemAnalyticsPresenter.MarketActionOutcome.NotAuthenticated ignored ->
                Toasts.failure("Your session has expired — please sign in again.");
            case SystemAnalyticsPresenter.MarketActionOutcome.Rejected r ->
                Toasts.failure("Couldn't " + (open ? "open" : "close") + " the market — please try again.");
            case SystemAnalyticsPresenter.MarketActionOutcome.Failure f ->
                Toasts.failure("Market action failed — please try again.");
        }
    }

    /** Refreshes status + KPI numbers (open, manual Refresh, and each poll tick); buttons untouched. */
    private void refreshData() {
        switch (presenter.load()) {
            case SystemAnalyticsPresenter.Outcome.Success ok -> {
                applyStatus(ok.market());
                statsSlot.removeAll();
                statsSlot.add(buildStats(ok.analytics()));
            }
            case SystemAnalyticsPresenter.Outcome.Failure f -> {
                statsSlot.removeAll();
                statsSlot.add(new LkBanner(LkBanner.Tone.warn, new LkIcon("warning", 18),
                    Lk.withReason("Could not load analytics", f.reason())));
            }
        }
    }

    private void applyStatus(MarketStateDTO market) {
        String status = market.currentStatus();
        statusBadge.setText(status);
        statusBadge.tone(toneFor(status));
        marketDetail.setText(String.format(
            "Payment gateway: %s · Ticket issuer: %s · Default admin: %s%s",
            market.paymentGatewayHealthy() ? "healthy" : "down",
            market.ticketIssuerHealthy() ? "healthy" : "down",
            market.defaultAdminPresent() ? "present" : "missing",
            market.lastOpenedAt() == null ? "" : " · Last opened: " + market.lastOpenedAt()));
    }

    private static LkBadge.Tone toneFor(String status) {
        if ("OPEN".equals(status)) return LkBadge.Tone.success;
        if ("CLOSED".equals(status)) return LkBadge.Tone.error;
        return LkBadge.Tone.warning;
    }

    private Component buildStats(SystemAnalyticsDTO a) {
        Div row = new Div();
        row.addClassName("ow-stats");
        row.add(
            new LkStat("Active visitors", String.valueOf(a.activeVisitors())),
            new LkStat("Visitor entry · /min", rate(a.visitorEntryRatePerMin())),
            new LkStat("Visitor exit · /min", rate(a.visitorExitRatePerMin())),
            new LkStat("New registrations · /min", rate(a.registrationRatePerMin())),
            new LkStat("Reservation rate · /min", rate(a.reservationRatePerMin())),
            new LkStat("Purchase rate · /min", rate(a.purchaseRatePerMin())),
            new LkStat("Reservation throughput · 1h", String.valueOf(a.reservationThroughputHr())),
            new LkStat("Purchase throughput · 1h", String.valueOf(a.purchaseThroughputHr()))
        );
        return row;
    }

    private static String rate(double v) {
        return String.format("%.2f", v);
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        int ms = presenter.refreshIntervalMs();
        if (ms > 0) {
            UI ui = attachEvent.getUI();
            ui.setPollInterval(ms);
            pollRegistration = ui.addPollListener(e -> refreshData());
        }
    }

    @Override
    protected void onDetach(DetachEvent detachEvent) {
        if (pollRegistration != null) {
            pollRegistration.remove();
            pollRegistration = null;
        }
        detachEvent.getUI().setPollInterval(-1);
        super.onDetach(detachEvent);
    }
}
