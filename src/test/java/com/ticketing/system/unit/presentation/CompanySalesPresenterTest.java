package com.ticketing.system.unit.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.organization.application.service.CompanyManagementService;
import com.ticketing.system.reporting.application.service.CompanyAnalyticsService;
import com.ticketing.system.sales.application.dto.PurchaseHistoryDTO;
import com.ticketing.system.shared.dto.MyCompanyDTO;
import com.ticketing.system.shared.exception.InvalidTokenException;
import com.ticketing.system.shared.exception.UnauthorizedActionException;
import com.ticketing.system.ui.presenters.company.CompanySalesPresenter;

class CompanySalesPresenterTest {

    private static final String TOKEN = "jwt-token";

    private CompanyManagementService companyManagementService;
    private CompanyAnalyticsService companyAnalyticsService;
    private CompanySalesPresenter presenter;

    @BeforeEach
    void setUp() {
        companyManagementService = mock(CompanyManagementService.class);
        companyAnalyticsService = mock(CompanyAnalyticsService.class);
        presenter = new CompanySalesPresenter(companyManagementService, companyAnalyticsService);
    }

    @Test
    void GivenNullToken_WhenLoad_ThenNotAuthenticatedWithoutCallingServices() {
        CompanySalesPresenter.Outcome outcome = presenter.load(null, null);

        assertInstanceOf(CompanySalesPresenter.Outcome.NotAuthenticated.class, outcome);
        verify(companyManagementService, never()).findMyCompanies(anyString());
        verify(companyAnalyticsService, never()).salesHistory(anyString(), anyInt());
    }

    @Test
    void GivenSelectedCompany_WhenLoad_ThenPassesCallerTokenToSalesHistory() {
        MyCompanyDTO acme = new MyCompanyDTO(7, "Acme", "Founder");
        MyCompanyDTO globex = new MyCompanyDTO(9, "Globex", "Manager");
        PurchaseHistoryDTO sales = new PurchaseHistoryDTO(List.of());
        when(companyManagementService.findMyCompanies(TOKEN)).thenReturn(List.of(acme, globex));
        when(companyAnalyticsService.salesHistory(TOKEN, 9)).thenReturn(sales);

        CompanySalesPresenter.Outcome outcome = presenter.load(TOKEN, 9);

        CompanySalesPresenter.Outcome.Success ok =
            assertInstanceOf(CompanySalesPresenter.Outcome.Success.class, outcome);
        assertEquals("Globex", ok.companyName());
        assertEquals(sales, ok.sales());
        verify(companyAnalyticsService).salesHistory(TOKEN, 9);
    }

    @Test
    void GivenMemberWithoutViewSales_WhenLoad_ThenReturnsFailure() {
        when(companyManagementService.findMyCompanies(TOKEN))
            .thenReturn(List.of(new MyCompanyDTO(7, "Acme", "Manager")));
        when(companyAnalyticsService.salesHistory(TOKEN, 7))
            .thenThrow(new UnauthorizedActionException("view this company's data"));

        CompanySalesPresenter.Outcome outcome = presenter.load(TOKEN, null);

        assertInstanceOf(CompanySalesPresenter.Outcome.Failure.class, outcome);
    }

    @Test
    void GivenExpiredToken_WhenLoad_ThenNotAuthenticated() {
        when(companyManagementService.findMyCompanies(TOKEN))
            .thenReturn(List.of(new MyCompanyDTO(7, "Acme", "Founder")));
        when(companyAnalyticsService.salesHistory(TOKEN, 7)).thenThrow(new InvalidTokenException("expired"));

        CompanySalesPresenter.Outcome outcome = presenter.load(TOKEN, null);

        assertInstanceOf(CompanySalesPresenter.Outcome.NotAuthenticated.class, outcome);
    }
}
