package com.ticketing.system.unit.presentation;

import com.ticketing.system.Core.Application.dto.CatalogSearchFiltersDTO;
import com.ticketing.system.Core.Application.dto.EventDetailDTO;
import com.ticketing.system.Core.Application.dto.MyCompanyDTO;
import com.ticketing.system.organization.application.service.CompanyManagementService;
import com.ticketing.system.catalog.application.service.EventManagementService;
import com.ticketing.system.catalog.domain.EventStatus;
import com.ticketing.system.catalog.domain.Location;
import com.ticketing.system.shared.exception.BusinessRuleViolationException;
import com.ticketing.system.shared.exception.InvalidStateTransitionException;
import com.ticketing.system.shared.exception.InvalidTokenException;
import com.ticketing.system.ui.presenters.company.CompanyEventListPresenter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class CompanyEventListPresenterTest {

    private static final String TOKEN = "owner-token";
    private static final int COMPANY_ID = 42;
    private static final int EVENT_ID = 7;

    private EventManagementService eventService;
    private CompanyManagementService companyService;
    private CompanyEventListPresenter presenter;

    @BeforeEach
    void setUp() {
        eventService = mock(EventManagementService.class);
        companyService = mock(CompanyManagementService.class);
        presenter = new CompanyEventListPresenter(eventService, companyService);
    }

    // ── load ──────────────────────────────────────────────────────────────────

    @Test
    void load_nullToken_returnsNotAuthenticated() {
        assertInstanceOf(CompanyEventListPresenter.Outcome.NotAuthenticated.class,
                presenter.load(null, null, CatalogSearchFiltersDTO.empty()));
        verifyNoInteractions(eventService, companyService);
    }

    @Test
    void load_invalidToken_returnsNotAuthenticated() {
        when(companyService.findMyCompanies(TOKEN)).thenThrow(new InvalidTokenException("bad"));

        assertInstanceOf(CompanyEventListPresenter.Outcome.NotAuthenticated.class,
                presenter.load(TOKEN, null, CatalogSearchFiltersDTO.empty()));
    }

    @Test
    void load_noCompanies_returnsNoCompany() {
        when(companyService.findMyCompanies(TOKEN)).thenReturn(List.of());

        assertInstanceOf(CompanyEventListPresenter.Outcome.NoCompany.class,
                presenter.load(TOKEN, null, CatalogSearchFiltersDTO.empty()));
        verifyNoInteractions(eventService);
    }

    @Test
    void load_success_returnsEventsForFirstCompany() {
        when(companyService.findMyCompanies(TOKEN)).thenReturn(List.of(company(COMPANY_ID)));
        List<EventDetailDTO> events = List.of(event(EVENT_ID, "Gala Night"));
        when(eventService.listEventsForCompany(eq(TOKEN), eq(COMPANY_ID), any())).thenReturn(events);

        CompanyEventListPresenter.Outcome.Success ok =
                assertInstanceOf(CompanyEventListPresenter.Outcome.Success.class,
                        presenter.load(TOKEN, null, CatalogSearchFiltersDTO.empty()));

        assertEquals(1, ok.events().size());
        assertEquals("Gala Night", ok.events().get(0).name());
    }

    @Test
    void load_managerMembership_returnsThatCompanysEvents() {
        // The exact bug (#429): a manager holds a Manager appointment, not an Owner one, so the old
        // owner-only lookup returned NoCompany. findMyCompanies keeps managers; the presenter must
        // resolve the company and serve its events.
        when(companyService.findMyCompanies(TOKEN)).thenReturn(List.of(company(COMPANY_ID, "Manager")));
        when(eventService.listEventsForCompany(eq(TOKEN), eq(COMPANY_ID), any()))
                .thenReturn(List.of(event(EVENT_ID, "Manager-Visible Show")));

        CompanyEventListPresenter.Outcome.Success ok =
                assertInstanceOf(CompanyEventListPresenter.Outcome.Success.class,
                        presenter.load(TOKEN, null, CatalogSearchFiltersDTO.empty()));

        assertEquals(1, ok.events().size());
        assertEquals("Manager-Visible Show", ok.events().get(0).name());
    }

    @Test
    void load_honorsSelectedCompanyAmongMemberships() {
        // Multiple memberships: the workspace's selected company id must win over "first".
        when(companyService.findMyCompanies(TOKEN))
                .thenReturn(List.of(company(99, "Co-owner"), company(COMPANY_ID, "Manager")));
        when(eventService.listEventsForCompany(eq(TOKEN), eq(COMPANY_ID), any())).thenReturn(List.of());

        presenter.load(TOKEN, COMPANY_ID, CatalogSearchFiltersDTO.empty());

        verify(eventService).listEventsForCompany(eq(TOKEN), eq(COMPANY_ID), any());
    }

    @Test
    void load_passesFiltersThroughToService() {
        when(companyService.findMyCompanies(TOKEN)).thenReturn(List.of(company(COMPANY_ID)));
        CatalogSearchFiltersDTO filters = new CatalogSearchFiltersDTO(
                "Othello", null, null, null, null, null, null, null, null, null, null, null, null);
        when(eventService.listEventsForCompany(eq(TOKEN), eq(COMPANY_ID), eq(filters))).thenReturn(List.of());

        presenter.load(TOKEN, null, filters);

        verify(eventService).listEventsForCompany(eq(TOKEN), eq(COMPANY_ID), eq(filters));
    }

    @Test
    void load_serviceThrowsRuntime_returnsFailure() {
        when(companyService.findMyCompanies(TOKEN)).thenReturn(List.of(company(COMPANY_ID)));
        when(eventService.listEventsForCompany(anyString(), anyInt(), any()))
                .thenThrow(new RuntimeException("db error"));

        CompanyEventListPresenter.Outcome.Failure fail =
                assertInstanceOf(CompanyEventListPresenter.Outcome.Failure.class,
                        presenter.load(TOKEN, null, CatalogSearchFiltersDTO.empty()));

        assertEquals("db error", fail.reason());
    }

    // ── cancelEvent ───────────────────────────────────────────────────────────

    @Test
    void cancelEvent_nullToken_returnsNotAuthenticated() {
        assertInstanceOf(CompanyEventListPresenter.ActionOutcome.NotAuthenticated.class,
                presenter.cancelEvent(null, EVENT_ID));
        verifyNoInteractions(eventService);
    }

    @Test
    void cancelEvent_invalidToken_returnsNotAuthenticated() {
        doThrow(new InvalidTokenException("bad")).when(eventService).cancelEventAndRefund(TOKEN, EVENT_ID);

        assertInstanceOf(CompanyEventListPresenter.ActionOutcome.NotAuthenticated.class,
                presenter.cancelEvent(TOKEN, EVENT_ID));
    }

    @Test
    void cancelEvent_success_callsServiceAndReturnsSuccess() {
        doNothing().when(eventService).cancelEventAndRefund(TOKEN, EVENT_ID);

        assertInstanceOf(CompanyEventListPresenter.ActionOutcome.Success.class,
                presenter.cancelEvent(TOKEN, EVENT_ID));

        verify(eventService).cancelEventAndRefund(TOKEN, EVENT_ID);
    }

    @Test
    void cancelEvent_serviceThrowsRuntime_returnsFailureWithMessage() {
        doThrow(new RuntimeException("refund failed")).when(eventService).cancelEventAndRefund(TOKEN, EVENT_ID);

        CompanyEventListPresenter.ActionOutcome.Failure fail =
                assertInstanceOf(CompanyEventListPresenter.ActionOutcome.Failure.class,
                        presenter.cancelEvent(TOKEN, EVENT_ID));

        assertEquals("refund failed", fail.reason());
    }

    // ── deleteEvent ───────────────────────────────────────────────────────────

    @Test
    void deleteEvent_nullToken_returnsNotAuthenticated() {
        assertInstanceOf(CompanyEventListPresenter.ActionOutcome.NotAuthenticated.class,
                presenter.deleteEvent(null, EVENT_ID));
        verifyNoInteractions(eventService);
    }

    @Test
    void deleteEvent_invalidToken_returnsNotAuthenticated() {
        doThrow(new InvalidTokenException("bad")).when(eventService).deleteEvent(TOKEN, EVENT_ID);

        assertInstanceOf(CompanyEventListPresenter.ActionOutcome.NotAuthenticated.class,
                presenter.deleteEvent(TOKEN, EVENT_ID));
    }

    @Test
    void deleteEvent_success_callsServiceAndReturnsSuccess() {
        doNothing().when(eventService).deleteEvent(TOKEN, EVENT_ID);

        assertInstanceOf(CompanyEventListPresenter.ActionOutcome.Success.class,
                presenter.deleteEvent(TOKEN, EVENT_ID));

        verify(eventService).deleteEvent(TOKEN, EVENT_ID);
    }

    @Test
    void deleteEvent_notCanceled_returnsFailureWithMessage() {
        doThrow(new InvalidStateTransitionException("Only CANCELED events can be deleted"))
                .when(eventService).deleteEvent(TOKEN, EVENT_ID);

        CompanyEventListPresenter.ActionOutcome.Failure fail =
                assertInstanceOf(CompanyEventListPresenter.ActionOutcome.Failure.class,
                        presenter.deleteEvent(TOKEN, EVENT_ID));

        assertEquals("Only CANCELED events can be deleted", fail.reason());
    }

    @Test
    void deleteEvent_withSalesHistory_returnsFailureWithMessage() {
        doThrow(new BusinessRuleViolationException("Can't delete an event with sales history"))
                .when(eventService).deleteEvent(TOKEN, EVENT_ID);

        CompanyEventListPresenter.ActionOutcome.Failure fail =
                assertInstanceOf(CompanyEventListPresenter.ActionOutcome.Failure.class,
                        presenter.deleteEvent(TOKEN, EVENT_ID));

        assertEquals("Can't delete an event with sales history", fail.reason());
    }

    // ── changeEventStatus ─────────────────────────────────────────────────────

    @Test
    void changeEventStatus_nullToken_returnsNotAuthenticated() {
        assertInstanceOf(CompanyEventListPresenter.ActionOutcome.NotAuthenticated.class,
                presenter.changeEventStatus(null, EVENT_ID, EventStatus.ON_SALE));
        verifyNoInteractions(eventService);
    }

    @Test
    void changeEventStatus_invalidToken_returnsNotAuthenticated() {
        doThrow(new InvalidTokenException("bad"))
                .when(eventService).changeEventStatus(TOKEN, EVENT_ID, EventStatus.ON_SALE);

        assertInstanceOf(CompanyEventListPresenter.ActionOutcome.NotAuthenticated.class,
                presenter.changeEventStatus(TOKEN, EVENT_ID, EventStatus.ON_SALE));
    }

    @Test
    void changeEventStatus_toOnSale_callsServiceAndReturnsSuccess() {
        doNothing().when(eventService).changeEventStatus(TOKEN, EVENT_ID, EventStatus.ON_SALE);

        assertInstanceOf(CompanyEventListPresenter.ActionOutcome.Success.class,
                presenter.changeEventStatus(TOKEN, EVENT_ID, EventStatus.ON_SALE));

        verify(eventService).changeEventStatus(eq(TOKEN), eq(EVENT_ID), eq(EventStatus.ON_SALE));
    }

    @Test
    void changeEventStatus_toScheduled_callsServiceAndReturnsSuccess() {
        doNothing().when(eventService).changeEventStatus(TOKEN, EVENT_ID, EventStatus.SCHEDULED);

        assertInstanceOf(CompanyEventListPresenter.ActionOutcome.Success.class,
                presenter.changeEventStatus(TOKEN, EVENT_ID, EventStatus.SCHEDULED));

        verify(eventService).changeEventStatus(eq(TOKEN), eq(EVENT_ID), eq(EventStatus.SCHEDULED));
    }

    @Test
    void changeEventStatus_serviceThrowsRuntime_returnsFailureWithMessage() {
        doThrow(new RuntimeException("invalid transition"))
                .when(eventService).changeEventStatus(TOKEN, EVENT_ID, EventStatus.ON_SALE);

        CompanyEventListPresenter.ActionOutcome.Failure fail =
                assertInstanceOf(CompanyEventListPresenter.ActionOutcome.Failure.class,
                        presenter.changeEventStatus(TOKEN, EVENT_ID, EventStatus.ON_SALE));

        assertEquals("invalid transition", fail.reason());
    }

    // ── countries / cities (owner-scope filter options) ───────────────────────

    @Test
    void countries_nullToken_isEmpty() {
        assertTrue(presenter.countries(null, null).isEmpty());
        verifyNoInteractions(eventService, companyService);
    }

    @Test
    void countries_noCompany_isEmpty() {
        when(companyService.findMyCompanies(TOKEN)).thenReturn(List.of());

        assertTrue(presenter.countries(TOKEN, null).isEmpty());
        verifyNoInteractions(eventService);
    }

    @Test
    void countries_distinctSortedFromCompanyEvents() {
        when(companyService.findMyCompanies(TOKEN)).thenReturn(List.of(company(COMPANY_ID)));
        when(eventService.listEventsForCompany(eq(TOKEN), eq(COMPANY_ID), any())).thenReturn(List.of(
                eventAt(1, "Israel", "Tel Aviv"),
                eventAt(2, "USA", "New York"),
                eventAt(3, "Israel", "Haifa")));

        assertEquals(List.of("Israel", "USA"), presenter.countries(TOKEN, null));
    }

    @Test
    void cities_filtersToCountryDistinctSorted() {
        when(companyService.findMyCompanies(TOKEN)).thenReturn(List.of(company(COMPANY_ID)));
        when(eventService.listEventsForCompany(eq(TOKEN), eq(COMPANY_ID), any())).thenReturn(List.of(
                eventAt(1, "Israel", "Tel Aviv"),
                eventAt(2, "Israel", "Haifa"),
                eventAt(4, "Israel", "Tel Aviv"),   // duplicate city
                eventAt(3, "USA", "New York")));

        assertEquals(List.of("Haifa", "Tel Aviv"), presenter.cities(TOKEN, null, "Israel"));
    }

    @Test
    void cities_nullCountry_isEmpty() {
        assertTrue(presenter.cities(TOKEN, null, null).isEmpty());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static MyCompanyDTO company(int id) {
        return company(id, "Co-owner");
    }

    private static MyCompanyDTO company(int id, String role) {
        return new MyCompanyDTO(id, "Company " + id, role);
    }

    private static EventDetailDTO eventAt(int id, String country, String city) {
        return new EventDetailDTO(String.valueOf(id), "E" + id, null, null, null,
                new Location(country, city), String.valueOf(COMPANY_ID), "Company " + COMPANY_ID,
                EventStatus.SCHEDULED, new ArrayList<>(), new ArrayList<>(), 0.0);
    }

    private static EventDetailDTO event(int id, String name) {
        return new EventDetailDTO(String.valueOf(id), name, null, null, null, null,
                String.valueOf(COMPANY_ID), "Company " + COMPANY_ID, EventStatus.SCHEDULED,
                new ArrayList<>(), new ArrayList<>(), 0.0);
    }
}
