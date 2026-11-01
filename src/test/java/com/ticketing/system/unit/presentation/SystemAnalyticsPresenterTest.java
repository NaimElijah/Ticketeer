package com.ticketing.system.unit.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.shared.dto.AuthTokenDTO;
import com.ticketing.system.shared.dto.MarketStateDTO;
import com.ticketing.system.shared.dto.SystemAnalyticsDTO;
import com.ticketing.system.governance.application.service.SystemAdminService;
import com.ticketing.system.governance.application.service.SystemAnalyticsService;
import com.ticketing.system.governance.config.AnalyticsProperties;
import com.ticketing.system.shared.exception.InvalidStateTransitionException;
import com.ticketing.system.shared.exception.MarketNotOpenException;
import com.ticketing.system.shared.exception.UnauthorizedActionException;
import com.ticketing.system.ui.presenters.admin.SystemAnalyticsPresenter;
import com.ticketing.system.ui.session.AuthSession;
import com.ticketing.system.ui.session.MapBackedVaadinSession;

/**
 * Unit tests for {@link SystemAnalyticsPresenter} (UC-46 / #43, #279). Both
 * application services are mocked; the tests assert the sealed-outcome mapping
 * the view switches on, including the null-token and domain-exception paths.
 */
class SystemAnalyticsPresenterTest {

    private static final String TOKEN = "admin-token";
    private static final int RATE_WINDOW_MINUTES = 5;
    private static final int REFRESH_INTERVAL_MS = 5000;
    private static final int ADMIN_ID = 1;
    // A fixed instant for the market state's recorded times (ADR-008): no test here reads them.
    private static final LocalDateTime MARKET_STATE_AT = LocalDateTime.of(2027, 7, 15, 17, 0);

    private SystemAdminService adminService;
    private SystemAnalyticsService analyticsService;
    private SystemAnalyticsPresenter presenter;

    @BeforeEach
    void setUp() {
        MapBackedVaadinSession.install();
        AuthSession.storeAuth(new AuthTokenDTO(TOKEN, 0L, ADMIN_ID, "admin", true));
        adminService = mock(SystemAdminService.class);
        analyticsService = mock(SystemAnalyticsService.class);
        presenter = new SystemAnalyticsPresenter(adminService, analyticsService,
                new AnalyticsProperties(RATE_WINDOW_MINUTES, REFRESH_INTERVAL_MS));
    }

    @AfterEach
    void tearDown() {
        MapBackedVaadinSession.uninstall();
    }

    @Test
    void load_returnsSuccess_withMarketAndAnalytics() {
        MarketStateDTO market = marketState("OPEN");
        SystemAnalyticsDTO analytics = zeroAnalytics();
        when(adminService.viewMarketState(TOKEN)).thenReturn(market);
        when(analyticsService.computeAnalytics(TOKEN)).thenReturn(analytics);

        SystemAnalyticsPresenter.Outcome outcome = presenter.load();

        SystemAnalyticsPresenter.Outcome.Success success =
                assertInstanceOf(SystemAnalyticsPresenter.Outcome.Success.class, outcome);
        assertEquals(market, success.market());
        assertEquals(analytics, success.analytics());
    }

    @Test
    void load_returnsFailure_whenAServiceThrows() {
        when(adminService.viewMarketState(TOKEN)).thenThrow(new RuntimeException("boom"));

        assertInstanceOf(SystemAnalyticsPresenter.Outcome.Failure.class, presenter.load());
    }

    @Test
    void openMarket_withNullToken_isNotAuthenticated_andDoesNotCallService() {
        SystemAnalyticsPresenter.MarketActionOutcome outcome = presenter.openMarket(null, "x");

        assertInstanceOf(SystemAnalyticsPresenter.MarketActionOutcome.NotAuthenticated.class, outcome);
        verify(adminService, never()).openMarket(any());
    }

    @Test
    void openMarket_success_returnsUpdatedState() {
        MarketStateDTO opened = marketState("OPEN");
        when(adminService.openMarket(any())).thenReturn(opened);

        SystemAnalyticsPresenter.MarketActionOutcome outcome = presenter.openMarket(TOKEN, "go");

        SystemAnalyticsPresenter.MarketActionOutcome.Success success =
                assertInstanceOf(SystemAnalyticsPresenter.MarketActionOutcome.Success.class, outcome);
        assertEquals(opened, success.market());
    }

    @Test
    void openMarket_unauthorized_mapsToNotAuthenticated() {
        when(adminService.openMarket(any())).thenThrow(new UnauthorizedActionException("nope"));

        assertInstanceOf(SystemAnalyticsPresenter.MarketActionOutcome.NotAuthenticated.class,
                presenter.openMarket(TOKEN, "go"));
    }

    @Test
    void openMarket_marketNotOpen_mapsToRejected() {
        when(adminService.openMarket(any())).thenThrow(new MarketNotOpenException("platform not initialized"));

        assertInstanceOf(SystemAnalyticsPresenter.MarketActionOutcome.Rejected.class,
                presenter.openMarket(TOKEN, "go"));
    }

    @Test
    void closeMarket_invalidTransition_mapsToRejected() {
        when(adminService.closeMarket(any()))
                .thenThrow(new InvalidStateTransitionException("market is not open; cannot close"));

        assertInstanceOf(SystemAnalyticsPresenter.MarketActionOutcome.Rejected.class,
                presenter.closeMarket(TOKEN, "stop"));
    }

    @Test
    void closeMarket_unexpectedError_mapsToFailure() {
        when(adminService.closeMarket(any())).thenThrow(new RuntimeException("db down"));

        assertInstanceOf(SystemAnalyticsPresenter.MarketActionOutcome.Failure.class,
                presenter.closeMarket(TOKEN, "stop"));
    }

    private static MarketStateDTO marketState(String status) {
        return new MarketStateDTO(status, MARKET_STATE_AT, MARKET_STATE_AT, true, true, MARKET_STATE_AT,
                true);
    }

    private static SystemAnalyticsDTO zeroAnalytics() {
        return new SystemAnalyticsDTO(0L, 0d, 0d, 0d, 0d, 0d, 0L, 0L, 0L, 0L, 0L, 5);
    }
}
