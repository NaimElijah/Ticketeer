package com.ticketing.system.ui.views.admin;

import com.ticketing.system.shared.dto.MarketStateDTO;
import com.ticketing.system.shared.dto.SystemAnalyticsDTO;
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
import com.ticketing.system.ui.support.DisplayLocale;
import com.ticketing.system.ui.support.DisplayTime;
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

import java.time.format.DateTimeFormatter;

/**
 * System Analytics dashboard (UC-46 / upstream#43, upstream#279). Admin-only page reached from
 * the "System Analytics" link in the platform-admin navigation. Shows the trading-market
 * status with Open / Close controls, plus live platform performance metrics
 * (visitor entry/exit, registrations, reservation/purchase rate and throughput).
 *
 * <p>View → Presenter → Service: the view renders by switching on the
 * presenter's sealed outcomes and never calls services directly. The KPI numbers
 * refresh on open, on the manual "Refresh" button, and on a poll tick
 * ({@link SystemAnalyticsPresenter#refreshIntervalMs()}); the Open/Close buttons
 * are built once so a refresh never drops a click. While the poll is on, a
 * "Pause auto-refresh" toggle stops and restarts it (#188, WCAG 2.2.2).
 *
 * <p>Served at {@code /admin/analytics}.
 */
@Route(value = "admin/analytics", layout = PlatformAdminLayout.class)
@PageTitle("System Analytics · Event Ticket Platform")
@PermitAll
@RequireCapability(Capability.ADMIN_WORKSPACE)
public class SystemAnalyticsView extends LkPage {

    private final SystemAnalyticsPresenter presenter;
    private final DisplayTime displayTime;
    private static final DateTimeFormatter OPENED_FMT = DateTimeFormatter.ofPattern("d MMM yyyy · HH:mm", DisplayLocale.PAGE);
    // Seconds, since the self-heal re-checks WSEP every 30 s by default.
    private static final DateTimeFormatter CHECKED_FMT = DateTimeFormatter.ofPattern("d MMM yyyy · HH:mm:ss", DisplayLocale.PAGE);
    private static final String LAST_UPDATED = "Last updated ";
    // One name in both states: aria-pressed says whether it is paused, and a name that flipped as well would
    // contradict it, read as "Resume auto-refresh, pressed".
    private static final String PAUSE_AUTO_REFRESH = "Pause auto-refresh";
    private static final String ARIA_PRESSED = "aria-pressed";
    private static final int NO_POLL = -1;

    private final LkBadge statusBadge = new LkBadge("—");
    private final Span marketDetail = new Span();
    private final Div statsSlot = new Div();
    /** When the figures were last loaded; a failed refresh leaves the last success's time. */
    private final Span lastUpdated = Lk.muted("");
    /** The poll cadence in ms; {@code 0} or less means no auto-refresh, and then no pause toggle. */
    private final int refreshIntervalMs;
    private final LkBtn pauseToggle = new LkBtn(PAUSE_AUTO_REFRESH).variant(LkBtn.Variant.secondary);
    private boolean paused;

    private Registration pollRegistration;

    /**
     * Creates the page and loads the market status and the analytics figures. When auto-refresh is
     * enabled, polling starts as the page is attached.
     */
    public SystemAnalyticsView(SystemAnalyticsPresenter presenter, DisplayTime displayTime) {
        this.presenter = presenter;
        this.displayTime = displayTime;
        this.refreshIntervalMs = presenter.refreshIntervalMs();

        title("System Analytics");
        subtitle("Real-time and historical platform performance metrics.");
        LkBtn refresh = new LkBtn("Refresh").variant(LkBtn.Variant.secondary).onClick(e -> refreshData());
        LkBtn back = new LkBtn("Back").variant(LkBtn.Variant.tertiary)
            .icon(new LkIcon("arrowLeft", 15))
            .onClick(e -> UI.getCurrent().navigate(AdminDashboardView.class));
        if (refreshIntervalMs > 0) {
            pauseToggle.getElement().setAttribute(ARIA_PRESSED, String.valueOf(paused));
            pauseToggle.onClick(e -> togglePause());
            actions(refresh, pauseToggle, back);
        } else {
            actions(refresh, back);
        }

        add(buildMarketCard());
        // The figures change on a timer, so the page says when they last did. Deliberately no live region: a
        // screen reader would read every refresh. The pause toggle stops the timer (#188, WCAG 2.2.2, 4.1.3).
        lastUpdated.getStyle().set("font-size", "12.5px");
        add(lastUpdated);
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
                Toasts.failure(Lk.withReason("Couldn't " + (open ? "open" : "close") + " the market", r.reason()));
            case SystemAnalyticsPresenter.MarketActionOutcome.Failure f ->
                Toasts.failure(Lk.withReason("Market action failed", f.reason()));
        }
    }

    /** Refreshes status + KPI numbers (open, manual Refresh, and each poll tick); buttons untouched. */
    private void refreshData() {
        switch (presenter.load()) {
            case SystemAnalyticsPresenter.Outcome.Success ok -> {
                applyStatus(ok.market());
                statsSlot.removeAll();
                statsSlot.add(buildStats(ok.analytics()));
                lastUpdated.setText(LAST_UPDATED + displayTime.formatNow(CHECKED_FMT));
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
        marketDetail.setText(String.format(DisplayLocale.PAGE,
            "%s · Default admin: %s%s",
            servicesText(market),
            market.defaultAdminPresent() ? "present" : "missing",
            market.lastOpenedAt() == null ? "" : " · Last opened: " + displayTime.format(market.lastOpenedAt(), OPENED_FMT)));
    }

    // The flags are a past probe's result, so say when; before any probe they mean nothing.
    private String servicesText(MarketStateDTO market) {
        if (market.lastProbedAt() == null) {
            return "Payment gateway and ticket issuer: not checked yet";
        }
        return String.format(DisplayLocale.PAGE, "Payment gateway: %s · Ticket issuer: %s (at last check, %s)",
            reachability(market.paymentGatewayHealthy()),
            reachability(market.ticketIssuerHealthy()),
            displayTime.format(market.lastProbedAt(), CHECKED_FMT));
    }

    private static String reachability(boolean reachable) {
        return reachable ? "reachable" : "unreachable";
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
        return String.format(DisplayLocale.PAGE, "%.2f", v);
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        if (refreshIntervalMs > 0 && !paused) {
            startPolling(attachEvent.getUI());
        }
    }

    @Override
    protected void onDetach(DetachEvent detachEvent) {
        stopPolling(detachEvent.getUI());
        super.onDetach(detachEvent);
    }

    private void togglePause() {
        paused = !paused;
        pauseToggle.getElement().setAttribute(ARIA_PRESSED, String.valueOf(paused));
        getUI().ifPresent(ui -> {
            if (paused) {
                stopPolling(ui);
            } else {
                startPolling(ui);
            }
        });
    }

    private void startPolling(UI ui) {
        if (pollRegistration == null) {
            ui.setPollInterval(refreshIntervalMs);
            pollRegistration = ui.addPollListener(e -> refreshData());
        }
    }

    // Stopping the interval too, not only the listener, so a paused page sends no requests either.
    private void stopPolling(UI ui) {
        if (pollRegistration != null) {
            pollRegistration.remove();
            pollRegistration = null;
        }
        ui.setPollInterval(NO_POLL);
    }
}
