package com.ticketing.system.unit.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.shared.dto.CompanyDashboardDTO;
import com.ticketing.system.shared.dto.MyCompanyDTO;
import com.ticketing.system.reporting.application.service.CompanyAnalyticsService;
import com.ticketing.system.organization.application.service.CompanyManagementService;
import com.ticketing.system.shared.exception.InvalidTokenException;
import com.ticketing.system.shared.exception.UnauthorizedActionException;
import com.ticketing.system.organization.application.dto.ProductionCompanyDTO;
import com.ticketing.system.ui.presenters.company.ManagerListPresenter;
import com.ticketing.system.ui.presenters.company.OwnerDashboardPresenter;
import com.ticketing.system.ui.session.CurrentCompanies;
import com.ticketing.system.ui.session.MapBackedVaadinSession;
import com.ticketing.system.ui.support.ServiceErrors;
import com.ticketing.system.shared.exception.SessionExpiredException;

class OwnerDashboardPresenterTest {

    private static final String TOKEN = "jwt-token";

    private CompanyManagementService companyManagementService;
    private CompanyAnalyticsService companyAnalyticsService;
    private OwnerDashboardPresenter presenter;

    @BeforeEach
    void setUp() {
        companyManagementService = mock(CompanyManagementService.class);
        companyAnalyticsService = mock(CompanyAnalyticsService.class);
        presenter = new OwnerDashboardPresenter(companyManagementService, companyAnalyticsService);
    }

    private static CompanyDashboardDTO stats() {
        return new CompanyDashboardDTO(1, 2, 3.0, 4, 4.5);
    }

    // The dashboard's company selector is where an owner of several companies picks one. The manager
    // screens act on the company selected in the workspace, so the selector's choice must reach it.
    @Test
    void GivenOwnerOfTwoCompanies_WhenTheDashboardSelectsTheSecond_ThenTheManagerScreensActOnIt() {
        MapBackedVaadinSession.install();
        try {
            when(companyManagementService.findOwnedCompanies(TOKEN)).thenReturn(List.of(
                new ProductionCompanyDTO(7, "Alpha", "desc", "ACTIVE", 1),
                new ProductionCompanyDTO(9, "Beta", "desc", "ACTIVE", 1)));
            when(companyManagementService.listManagers(TOKEN, 9)).thenReturn(List.of());
            when(companyManagementService.listPendingInvitations(TOKEN, 9)).thenReturn(List.of());

            presenter.selectCompany(9);

            assertEquals(9, CurrentCompanies.currentCompanyId(), "the selection is the workspace's");
            ManagerListPresenter.Outcome.Success roster = assertInstanceOf(ManagerListPresenter.Outcome.Success.class,
                new ManagerListPresenter(companyManagementService).loadRoster(TOKEN));
            assertEquals("Beta", roster.companyName(), "the manager roster is the selected company's");
        } finally {
            MapBackedVaadinSession.uninstall();
        }
    }

    @Test
    void GivenANullToken_WhenLoadFor_ThenReturnsNotAuthenticatedWithoutCallingServices() {
        OwnerDashboardPresenter.Outcome outcome = presenter.loadFor(null, null);

        assertInstanceOf(OwnerDashboardPresenter.Outcome.NotAuthenticated.class, outcome);
        verifyNoInteractions(companyManagementService, companyAnalyticsService);
    }

    @Test
    void GivenNoCompanies_WhenLoadFor_ThenReturnsNoCompanyWithoutLoadingTheDashboard() {
        when(companyManagementService.findMyCompanies(TOKEN)).thenReturn(List.of());

        OwnerDashboardPresenter.Outcome outcome = presenter.loadFor(TOKEN, null);

        assertInstanceOf(OwnerDashboardPresenter.Outcome.NoCompany.class, outcome);
        verify(companyAnalyticsService, never()).dashboard(anyString(), anyInt());
    }

    @Test
    void GivenANullCompanyId_WhenLoadFor_ThenDefaultsToFirstCompany() {
        MyCompanyDTO first = new MyCompanyDTO(7, "Acme", "Founder");
        MyCompanyDTO second = new MyCompanyDTO(9, "Globex", "Manager");
        when(companyManagementService.findMyCompanies(TOKEN)).thenReturn(List.of(first, second));
        when(companyAnalyticsService.dashboard(TOKEN, 7)).thenReturn(stats());

        OwnerDashboardPresenter.Outcome outcome = presenter.loadFor(TOKEN, null);

        OwnerDashboardPresenter.Outcome.Success ok =
            assertInstanceOf(OwnerDashboardPresenter.Outcome.Success.class, outcome);
        assertEquals(first, ok.selected());
        assertEquals(List.of(first, second), ok.companies());
        verify(companyAnalyticsService).dashboard(TOKEN, 7);
    }

    @Test
    void GivenAnExplicitCompanyId_WhenLoadFor_ThenSelectsThatCompany() {
        MyCompanyDTO first = new MyCompanyDTO(7, "Acme", "Founder");
        MyCompanyDTO second = new MyCompanyDTO(9, "Globex", "Manager");
        when(companyManagementService.findMyCompanies(TOKEN)).thenReturn(List.of(first, second));
        when(companyAnalyticsService.dashboard(TOKEN, 9)).thenReturn(stats());

        OwnerDashboardPresenter.Outcome outcome = presenter.loadFor(TOKEN, 9);

        OwnerDashboardPresenter.Outcome.Success ok =
            assertInstanceOf(OwnerDashboardPresenter.Outcome.Success.class, outcome);
        assertEquals(second, ok.selected());
        verify(companyAnalyticsService).dashboard(TOKEN, 9);
    }

    @Test
    void GivenAnInvalidToken_WhenLoadFor_ThenReturnsNotAuthenticated() {
        when(companyManagementService.findMyCompanies(TOKEN)).thenThrow(new InvalidTokenException("bad"));

        OwnerDashboardPresenter.Outcome outcome = presenter.loadFor(TOKEN, null);

        assertInstanceOf(OwnerDashboardPresenter.Outcome.NotAuthenticated.class, outcome);
    }

    @Test
    void GivenCallerNoLongerMember_WhenLoadFor_ThenReturnsFailure() {
        MyCompanyDTO first = new MyCompanyDTO(7, "Acme", "Manager");
        when(companyManagementService.findMyCompanies(TOKEN)).thenReturn(List.of(first));
        when(companyAnalyticsService.dashboard(TOKEN, 7))
            .thenThrow(new UnauthorizedActionException("view this company's data"));

        OwnerDashboardPresenter.Outcome outcome = presenter.loadFor(TOKEN, null);

        assertInstanceOf(OwnerDashboardPresenter.Outcome.Failure.class, outcome);
    }

    @Test
    void GivenTheServiceFailsInternally_WhenLoadFor_ThenReturnsGenericFailure() {
        when(companyManagementService.findMyCompanies(TOKEN)).thenThrow(new RuntimeException("backend down"));

        OwnerDashboardPresenter.Outcome outcome = presenter.loadFor(TOKEN, null);

        OwnerDashboardPresenter.Outcome.Failure fail =
            assertInstanceOf(OwnerDashboardPresenter.Outcome.Failure.class, outcome);
        assertEquals(ServiceErrors.GENERIC_FAILURE_MESSAGE, fail.reason());
    }

    // ---- an expired session reads as a dead one, as a revoked token does (ADR-025, #189) ----

    @Test
    void GivenAnExpiredSession_WhenLoadFor_ThenReturnsNotAuthenticated() {
        when(companyManagementService.findMyCompanies(TOKEN)).thenThrow(new SessionExpiredException());

        assertInstanceOf(OwnerDashboardPresenter.Outcome.NotAuthenticated.class, presenter.loadFor(TOKEN, null));
    }
}
