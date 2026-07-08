package com.ticketing.system.unit.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.Core.Application.dto.AdminOverviewDTO;
import com.ticketing.system.governance.application.service.SystemAnalyticsService;
import com.ticketing.system.ui.presenters.admin.AdminDashboardPresenter;

/**
 * Unit tests for {@link AdminDashboardPresenter} (#279). The analytics service is mocked; the
 * tests assert the sealed-outcome mapping the admin landing view switches on.
 */
class AdminDashboardPresenterTest {

    private SystemAnalyticsService analyticsService;
    private AdminDashboardPresenter presenter;

    @BeforeEach
    void setUp() {
        analyticsService = mock(SystemAnalyticsService.class);
        presenter = new AdminDashboardPresenter(analyticsService);
    }

    @Test
    void load_returnsSuccess_withOverview() {
        AdminOverviewDTO overview = new AdminOverviewDTO(2, 3, 1, 1500.0);
        when(analyticsService.adminOverview()).thenReturn(overview);

        AdminDashboardPresenter.Outcome outcome = presenter.load();

        AdminDashboardPresenter.Outcome.Success success =
                assertInstanceOf(AdminDashboardPresenter.Outcome.Success.class, outcome);
        assertEquals(overview, success.overview());
    }

    @Test
    void load_returnsFailure_whenServiceThrows() {
        when(analyticsService.adminOverview()).thenThrow(new RuntimeException("boom"));

        assertInstanceOf(AdminDashboardPresenter.Outcome.Failure.class, presenter.load());
    }
}
