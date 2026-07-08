package com.ticketing.system.ui.presenters.admin;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.ticketing.system.shared.dto.MarketControlRequestDTO;
import com.ticketing.system.shared.dto.MarketStateDTO;
import com.ticketing.system.shared.dto.SystemAnalyticsDTO;
import com.ticketing.system.governance.application.service.SystemAdminService;
import com.ticketing.system.governance.application.service.SystemAnalyticsService;
import com.ticketing.system.shared.exception.InvalidStateTransitionException;
import com.ticketing.system.shared.exception.MarketNotOpenException;
import com.ticketing.system.shared.exception.UnauthorizedActionException;

/**
 * MVP presenter for {@code SystemAnalyticsView} (UC-46 / #43, #279). Vaadin-free
 * POJO: combines the market snapshot ({@link SystemAdminService#viewMarketState()})
 * with the live platform metrics ({@link SystemAnalyticsService#computeAnalytics()})
 * and translates market open/close calls into a sealed outcome the view switches on.
 *
 * <p>Mirrors {@code OwnerDashboardPresenter}, which likewise combines two
 * application services behind one presenter.
 */
@Component
public class SystemAnalyticsPresenter {

    private final SystemAdminService systemAdminService;
    private final SystemAnalyticsService systemAnalyticsService;
    private final int refreshIntervalMs;

    @Autowired
    public SystemAnalyticsPresenter(SystemAdminService systemAdminService,
                                    SystemAnalyticsService systemAnalyticsService,
                                    @Value("${analytics.refresh-interval-ms:5000}") int refreshIntervalMs) {
        this.systemAdminService = systemAdminService;
        this.systemAnalyticsService = systemAnalyticsService;
        this.refreshIntervalMs = refreshIntervalMs;
    }

    /** Poll cadence (ms) the view uses to auto-refresh; {@code <= 0} disables auto-refresh. */
    public int refreshIntervalMs() {
        return refreshIntervalMs;
    }

    /** Loads the market snapshot + live analytics for the dashboard. */
    public Outcome load() {
        try {
            MarketStateDTO market = systemAdminService.viewMarketState();
            SystemAnalyticsDTO analytics = systemAnalyticsService.computeAnalytics();
            return new Outcome.Success(market, analytics);
        } catch (RuntimeException e) {
            return new Outcome.Failure(e.getMessage());
        }
    }

    public MarketActionOutcome openMarket(String token, String reason) {
        return marketAction(token, "OPEN", reason);
    }

    public MarketActionOutcome closeMarket(String token, String reason) {
        return marketAction(token, "CLOSE", reason);
    }

    private MarketActionOutcome marketAction(String token, String action, String reason) {
        if (token == null || token.isBlank()) {
            return new MarketActionOutcome.NotAuthenticated();
        }
        try {
            MarketControlRequestDTO request = new MarketControlRequestDTO(action, reason, token);
            MarketStateDTO state = "OPEN".equals(action)
                    ? systemAdminService.openMarket(request)
                    : systemAdminService.closeMarket(request);
            return new MarketActionOutcome.Success(state);
        } catch (UnauthorizedActionException e) {
            return new MarketActionOutcome.NotAuthenticated();
        } catch (MarketNotOpenException | InvalidStateTransitionException e) {
            return new MarketActionOutcome.Rejected(e.getMessage());
        } catch (RuntimeException e) {
            return new MarketActionOutcome.Failure(e.getMessage());
        }
    }

    /** Sealed outcome for the dashboard load. */
    public sealed interface Outcome {
        record Success(MarketStateDTO market, SystemAnalyticsDTO analytics) implements Outcome { }
        record Failure(String reason) implements Outcome { }
    }

    /** Sealed outcome for an Open/Close Market action. */
    public sealed interface MarketActionOutcome {
        record Success(MarketStateDTO market) implements MarketActionOutcome { }
        record NotAuthenticated() implements MarketActionOutcome { }
        record Rejected(String reason) implements MarketActionOutcome { }
        record Failure(String reason) implements MarketActionOutcome { }
    }
}
