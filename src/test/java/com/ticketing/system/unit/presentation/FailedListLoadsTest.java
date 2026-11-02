package com.ticketing.system.unit.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import com.ticketing.system.catalog.application.service.CatalogService;
import com.ticketing.system.catalog.application.service.EventManagementService;
import com.ticketing.system.governance.application.service.SystemAdminService;
import com.ticketing.system.organization.application.service.CompanyManagementService;
import com.ticketing.system.reporting.application.service.SalesSummaryCalculator;
import com.ticketing.system.shared.dto.AuthTokenDTO;
import com.ticketing.system.shared.dto.CatalogSearchFiltersDTO;
import com.ticketing.system.shared.dto.MyCompanyDTO;
import com.ticketing.system.ui.presenters.ListOutcome;
import com.ticketing.system.ui.presenters.admin.GlobalHistoryPresenter;
import com.ticketing.system.ui.presenters.catalog.BrowseEventsPresenter;
import com.ticketing.system.ui.presenters.company.CompanyEventListPresenter;
import com.ticketing.system.ui.presenters.company.PurchasePolicyEditorPresenter;
import com.ticketing.system.ui.session.AuthSession;
import com.ticketing.system.ui.session.MapBackedVaadinSession;
import com.ticketing.system.ui.session.SessionIdentity;
import com.ticketing.system.ui.support.ServiceErrors;

class FailedListLoadsTest {

    private static final String TOKEN = "jwt";
    private static final int COMPANY_ID = 7;
    private static final DataAccessResourceFailureException OUTAGE =
            new DataAccessResourceFailureException("connection refused");

    @AfterEach
    void tearDown() {
        MapBackedVaadinSession.uninstall();
    }

    // ---- UI-B3: failed loads read as failures, not as empty lists (#187) ----

    @Test
    void GivenTheStoreIsDown_WhenGlobalHistoryLoads_ThenFailureCarriesTheOutageMessage() {
        MapBackedVaadinSession.install();
        AuthSession.storeAuth(new AuthTokenDTO(TOKEN, 0L, 1, "admin", true));
        SystemAdminService admin = mock(SystemAdminService.class);
        when(admin.viewGlobalHistory(eq(TOKEN), any())).thenThrow(OUTAGE);

        var outcome = new GlobalHistoryPresenter(admin, mock(SalesSummaryCalculator.class)).loadAllRecords();

        assertFailureWithOutageText(outcome);
    }

    @Test
    void GivenTheStoreIsDown_WhenBrowseSearches_ThenFailureCarriesTheOutageMessage() {
        CatalogService catalog = mock(CatalogService.class);
        when(catalog.searchGlobal(any(), any())).thenThrow(OUTAGE);

        assertFailureWithOutageText(new BrowseEventsPresenter(catalog).search(TOKEN, CatalogSearchFiltersDTO.empty()));
    }

    @Test
    void GivenTheStoreIsDown_WhenBrowseListsCountries_ThenFailureCarriesTheOutageMessage() {
        CatalogService catalog = mock(CatalogService.class);
        when(catalog.onSaleCountries()).thenThrow(OUTAGE);

        assertFailureWithOutageText(new BrowseEventsPresenter(catalog).countries());
    }

    @Test
    void GivenTheStoreIsDown_WhenBrowseListsCities_ThenFailureCarriesTheOutageMessage() {
        CatalogService catalog = mock(CatalogService.class);
        when(catalog.onSaleCitiesInCountry("Israel")).thenThrow(OUTAGE);

        assertFailureWithOutageText(new BrowseEventsPresenter(catalog).cities("Israel"));
    }

    @Test
    void GivenTheStoreIsDown_WhenThePolicyEditorListsEvents_ThenFailureCarriesTheOutageMessage() {
        EventManagementService events = mock(EventManagementService.class);
        when(events.listEventsForCompany(TOKEN, COMPANY_ID)).thenThrow(OUTAGE);

        var outcome = new PurchasePolicyEditorPresenter(mock(CompanyManagementService.class), events,
                mock(SessionIdentity.class)).listEvents(TOKEN, COMPANY_ID);

        assertFailureWithOutageText(outcome);
    }

    @Test
    void GivenTheStoreIsDown_WhenTheCompanyEventListListsCountries_ThenFailureCarriesTheOutageMessage() {
        assertFailureWithOutageText(companyEventListWhoseEventsFail().countries(TOKEN, COMPANY_ID));
    }

    @Test
    void GivenTheStoreIsDown_WhenTheCompanyEventListListsCities_ThenFailureCarriesTheOutageMessage() {
        assertFailureWithOutageText(companyEventListWhoseEventsFail().cities(TOKEN, COMPANY_ID, "Israel"));
    }

    private static CompanyEventListPresenter companyEventListWhoseEventsFail() {
        CompanyManagementService companies = mock(CompanyManagementService.class);
        when(companies.findMyCompanies(TOKEN)).thenReturn(List.of(new MyCompanyDTO(COMPANY_ID, "Acme", "FOUNDER")));
        EventManagementService events = mock(EventManagementService.class);
        when(events.listEventsForCompany(eq(TOKEN), anyInt(), any())).thenThrow(OUTAGE);
        return new CompanyEventListPresenter(events, companies);
    }

    private static void assertFailureWithOutageText(Object outcome) {
        ListOutcome.Failure<?> failure = assertInstanceOf(ListOutcome.Failure.class, outcome,
                "a store failure must not read as an empty list, but was: " + outcome);
        assertEquals(ServiceErrors.DB_UNAVAILABLE_MESSAGE, failure.reason());
    }
}
