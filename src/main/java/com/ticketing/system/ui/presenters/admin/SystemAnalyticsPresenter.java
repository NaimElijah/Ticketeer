package com.ticketing.system.ui.presenters.admin;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.ticketing.system.shared.dto.MarketControlRequestDTO;
import com.ticketing.system.shared.dto.MarketStateDTO;
import com.ticketing.system.shared.dto.SystemAnalyticsDTO;
import com.ticketing.system.governance.application.service.SystemAdminService;
import com.ticketing.system.governance.application.service.SystemAnalyticsService;
import com.ticketing.system.governance.config.AnalyticsProperties;
import com.ticketing.system.shared.exception.InvalidStateTransitionException;
import com.ticketing.system.shared.exception.InvalidTokenException;
import com.ticketing.system.shared.exception.MarketNotOpenException;
import com.ticketing.system.shared.exception.UnauthorizedActionException;
import com.ticketing.system.ui.session.AuthSession;
import com.ticketing.system.ui.support.ServiceErrors;

/**
 * MVP presenter for {@code SystemAnalyticsView} (UC-46 / upstream#43, upstream#279). Vaadin-free
 * POJO: combines the market snapshot ({@link SystemAdminService#viewMarketState(String)})
 * with the live platform metrics ({@link SystemAnalyticsService#computeAnalytics(String)}), both read
 * with the signed-in admin's token,
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

    /**
     * Creates the presenter and reads the poll cadence once.
     *
     * @param analyticsProperties source of {@link #refreshIntervalMs()}
     */
    @Autowired
    public SystemAnalyticsPresenter(SystemAdminService systemAdminService,
                                    SystemAnalyticsService systemAnalyticsService,
                                    AnalyticsProperties analyticsProperties) {
        this.systemAdminService = systemAdminService;
        this.systemAnalyticsService = systemAnalyticsService;
        this.refreshIntervalMs = analyticsProperties.refreshIntervalMs();
    }

    /** Poll cadence (ms) the view uses to auto-refresh; {@code 0} disables auto-refresh. */
    public int refreshIntervalMs() {
        return refreshIntervalMs;
    }

    /**
     * Loads the market snapshot + live analytics for the dashboard with the signed-in admin's token.
     *
     * @return {@code Success} with both, or {@code Failure} with a {@link ServiceErrors} message when
     *         either service refuses the token or fails
     */
    public Outcome load() {
        try {
            String adminToken = AuthSession.token();
            MarketStateDTO market = systemAdminService.viewMarketState(adminToken);
            SystemAnalyticsDTO analytics = systemAnalyticsService.computeAnalytics(adminToken);
            return new Outcome.Success(market, analytics);
        } catch (RuntimeException e) {
            return new Outcome.Failure(ServiceErrors.userMessage(e));
        }
    }

    /**
     * Opens the market.
     *
     * @param token  the admin's token; {@code null} or blank gives {@link MarketActionOutcome.NotAuthenticated}
     * @param reason the admin's stated reason, passed to the service as given
     * @return {@link MarketActionOutcome.Success} with the new market state, or the outcome for the
     *     refusal or failure; see {@link MarketActionOutcome}
     */
    public MarketActionOutcome openMarket(String token, String reason) {
        return marketAction(token, "OPEN", reason);
    }

    /**
     * Closes the market.
     *
     * @param token  the admin's token; {@code null} or blank gives {@link MarketActionOutcome.NotAuthenticated}
     * @param reason the admin's stated reason, passed to the service as given
     * @return {@link MarketActionOutcome.Success} with the new market state, or the outcome for the
     *     refusal or failure; see {@link MarketActionOutcome}
     */
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
        } catch (UnauthorizedActionException | InvalidTokenException e) {
            return new MarketActionOutcome.NotAuthenticated();
        } catch (MarketNotOpenException | InvalidStateTransitionException e) {
            return new MarketActionOutcome.Rejected(e.getMessage());
        } catch (RuntimeException e) {
            return new MarketActionOutcome.Failure(ServiceErrors.userMessage(e));
        }
    }

    /** Sealed outcome for the dashboard load. */
    public sealed interface Outcome {
        /**
         * Both reads succeeded.
         *
         * @param market    the market's current state
         * @param analytics the live platform metrics
         */
        record Success(MarketStateDTO market, SystemAnalyticsDTO analytics) implements Outcome { }

        /**
         * Either read failed, including a token the service refused.
         *
         * @param reason the text the user may read, from {@code ServiceErrors.userMessage}
         */
        record Failure(String reason) implements Outcome { }
    }

    /** Sealed outcome for an Open/Close Market action. */
    public sealed interface MarketActionOutcome {
        /**
         * The service carried out the action.
         *
         * @param market the market's state after the action
         */
        record Success(MarketStateDTO market) implements MarketActionOutcome { }

        /**
         * The token is missing or blank, or the service refused it as invalid or as not allowed to
         * control the market.
         */
        record NotAuthenticated() implements MarketActionOutcome { }

        /**
         * The market is not in a state the action applies to ({@code MarketNotOpenException} or
         * {@code InvalidStateTransitionException}).
         *
         * @param reason the exception's message, which reaches the user
         */
        record Rejected(String reason) implements MarketActionOutcome { }

        /**
         * Any other failure.
         *
         * @param reason the text the user may read, from {@code ServiceErrors.userMessage}
         */
        record Failure(String reason) implements MarketActionOutcome { }
    }
}
