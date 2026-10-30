package com.ticketing.system.unit.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.shared.dto.AdminOverviewDTO;
import com.ticketing.system.shared.dto.AuthTokenDTO;
import com.ticketing.system.governance.application.service.SystemAnalyticsService;
import com.ticketing.system.ui.presenters.admin.AdminDashboardPresenter;
import com.ticketing.system.ui.session.AuthSession;
import com.ticketing.system.ui.session.MapBackedVaadinSession;

/**
 * Unit tests for {@link AdminDashboardPresenter} (#279). The analytics service is mocked; the
 * tests assert the sealed-outcome mapping the admin landing view switches on.
 */
class AdminDashboardPresenterTest {

    private static final String ADMIN_TOKEN = "admin-token";
    private static final int ADMIN_ID = 1;

    private SystemAnalyticsService analyticsService;
    private AdminDashboardPresenter presenter;

    @BeforeEach
    void setUp() {
        MapBackedVaadinSession.install();
        AuthSession.storeAuth(new AuthTokenDTO(ADMIN_TOKEN, 0L, ADMIN_ID, "admin", true));
        analyticsService = mock(SystemAnalyticsService.class);
        presenter = new AdminDashboardPresenter(analyticsService);
    }

    @AfterEach
    void tearDown() {
        MapBackedVaadinSession.uninstall();
    }

    @Test
    void load_returnsSuccess_withOverview() {
        AdminOverviewDTO overview = new AdminOverviewDTO(2, 3, 1, 1500.0);
        when(analyticsService.adminOverview(ADMIN_TOKEN)).thenReturn(overview);

        AdminDashboardPresenter.Outcome outcome = presenter.load();

        AdminDashboardPresenter.Outcome.Success success =
                assertInstanceOf(AdminDashboardPresenter.Outcome.Success.class, outcome);
        assertEquals(overview, success.overview());
    }

    @Test
    void load_returnsFailure_whenServiceThrows() {
        when(analyticsService.adminOverview(ADMIN_TOKEN)).thenThrow(new RuntimeException("boom"));

        assertInstanceOf(AdminDashboardPresenter.Outcome.Failure.class, presenter.load());
    }
}
