package com.ticketing.system.unit.presentation;

import jakarta.persistence.PersistenceException;

import com.ticketing.system.shared.dto.CatalogSearchFiltersDTO;
import com.ticketing.system.catalog.application.dto.EventDetailDTO;
import com.ticketing.system.shared.dto.MyCompanyDTO;
import com.ticketing.system.organization.application.service.CompanyManagementService;
import com.ticketing.system.catalog.application.service.EventManagementService;
import com.ticketing.system.catalog.domain.EventStatus;
import com.ticketing.system.catalog.domain.Location;
import com.ticketing.system.shared.exception.BusinessRuleViolationException;
import com.ticketing.system.shared.exception.InvalidStateTransitionException;
import com.ticketing.system.shared.exception.InvalidTokenException;
import com.ticketing.system.shared.exception.RefundFailedException;
import com.ticketing.system.shared.exception.RefundGatewayUnreachableException;
import com.ticketing.system.shared.exception.RefundOutcomeUnknownException;
import com.ticketing.system.ui.presenters.ListOutcome;
import com.ticketing.system.ui.presenters.company.CompanyEventListPresenter;
import com.ticketing.system.ui.support.ServiceErrors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import com.ticketing.system.shared.exception.SessionExpiredException;

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
    void GivenANullToken_WhenLoad_ThenReturnsNotAuthenticatedWithoutCallingServices() {
        assertInstanceOf(CompanyEventListPresenter.Outcome.NotAuthenticated.class,
                presenter.load(null, null, CatalogSearchFiltersDTO.empty()));
        verifyNoInteractions(eventService, companyService);
    }

    @Test
    void GivenAnInvalidToken_WhenLoad_ThenReturnsNotAuthenticated() {
        when(companyService.findMyCompanies(TOKEN)).thenThrow(new InvalidTokenException("bad"));

        assertInstanceOf(CompanyEventListPresenter.Outcome.NotAuthenticated.class,
                presenter.load(TOKEN, null, CatalogSearchFiltersDTO.empty()));
    }

    @Test
    void GivenNoCompanies_WhenLoad_ThenReturnsNoCompanyWithoutCallingEventService() {
        when(companyService.findMyCompanies(TOKEN)).thenReturn(List.of());

        assertInstanceOf(CompanyEventListPresenter.Outcome.NoCompany.class,
                presenter.load(TOKEN, null, CatalogSearchFiltersDTO.empty()));
        verifyNoInteractions(eventService);
    }

    @Test
    void GivenOneCompanyAndNoSelection_WhenLoad_ThenReturnsEventsForThatCompany() {
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
    void GivenAManagerMembership_WhenLoad_ThenReturnsThatCompanysEvents() {
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
    void GivenASelectedCompanyAmongMemberships_WhenLoad_ThenHonorsTheSelectedCompany() {
        // Multiple memberships: the workspace's selected company id must win over "first".
        when(companyService.findMyCompanies(TOKEN))
                .thenReturn(List.of(company(99, "Co-owner"), company(COMPANY_ID, "Manager")));
        when(eventService.listEventsForCompany(eq(TOKEN), eq(COMPANY_ID), any())).thenReturn(List.of());

        presenter.load(TOKEN, COMPANY_ID, CatalogSearchFiltersDTO.empty());

        verify(eventService).listEventsForCompany(eq(TOKEN), eq(COMPANY_ID), any());
    }

    @Test
    void GivenSearchFilters_WhenLoad_ThenPassesFiltersThroughToService() {
        when(companyService.findMyCompanies(TOKEN)).thenReturn(List.of(company(COMPANY_ID)));
        CatalogSearchFiltersDTO filters = new CatalogSearchFiltersDTO(
                "Othello", null, null, null, null, null, null, null, null, null, null, null, null);
        when(eventService.listEventsForCompany(eq(TOKEN), eq(COMPANY_ID), eq(filters))).thenReturn(List.of());

        presenter.load(TOKEN, null, filters);

        verify(eventService).listEventsForCompany(eq(TOKEN), eq(COMPANY_ID), eq(filters));
    }

    @Test
    void GivenTheServiceThrowsRuntime_WhenLoad_ThenReturnsGenericFailure() {
        when(companyService.findMyCompanies(TOKEN)).thenReturn(List.of(company(COMPANY_ID)));
        when(eventService.listEventsForCompany(anyString(), anyInt(), any()))
                .thenThrow(new RuntimeException("db error"));

        CompanyEventListPresenter.Outcome.Failure fail =
                assertInstanceOf(CompanyEventListPresenter.Outcome.Failure.class,
                        presenter.load(TOKEN, null, CatalogSearchFiltersDTO.empty()));

        assertEquals(ServiceErrors.GENERIC_FAILURE_MESSAGE, fail.reason());
    }

    @Test
    void GivenTheEventListFailsInternally_WhenLoad_ThenTheReasonIsTheGenericText() {
        // A non-domain failure is an internal error: its text must not reach the user (CWE-209).
        when(companyService.findMyCompanies(TOKEN)).thenReturn(List.of(company(COMPANY_ID)));
        when(eventService.listEventsForCompany(anyString(), anyInt(), any()))
                .thenThrow(new PersistenceException("could not execute query [select e.event_id from events e]"));

        CompanyEventListPresenter.Outcome.Failure fail =
                assertInstanceOf(CompanyEventListPresenter.Outcome.Failure.class,
                        presenter.load(TOKEN, null, CatalogSearchFiltersDTO.empty()));

        assertEquals(ServiceErrors.GENERIC_FAILURE_MESSAGE, fail.reason());
    }

    @Test
    void GivenCancellingFailsInternally_WhenCancelEvent_ThenTheReasonIsTheGenericText() {
        doThrow(new NullPointerException("Cannot invoke \"Event.getVenueMap()\" because \"event\" is null"))
                .when(eventService).cancelEventAndRefund(TOKEN, EVENT_ID);

        CompanyEventListPresenter.CancelOutcome.Failure fail =
                assertInstanceOf(CompanyEventListPresenter.CancelOutcome.Failure.class,
                        presenter.cancelEvent(TOKEN, EVENT_ID));

        assertEquals(ServiceErrors.GENERIC_FAILURE_MESSAGE, fail.reason());
    }

    // ── cancelEvent ───────────────────────────────────────────────────────────

    @Test
    void GivenANullToken_WhenCancelEvent_ThenReturnsNotAuthenticatedWithoutCallingService() {
        assertInstanceOf(CompanyEventListPresenter.CancelOutcome.NotAuthenticated.class,
                presenter.cancelEvent(null, EVENT_ID));
        verifyNoInteractions(eventService);
    }

    @Test
    void GivenAnInvalidToken_WhenCancelEvent_ThenReturnsNotAuthenticated() {
        doThrow(new InvalidTokenException("bad")).when(eventService).cancelEventAndRefund(TOKEN, EVENT_ID);

        assertInstanceOf(CompanyEventListPresenter.CancelOutcome.NotAuthenticated.class,
                presenter.cancelEvent(TOKEN, EVENT_ID));
    }

    @Test
    void GivenTheServiceAcceptsTheCancel_WhenCancelEvent_ThenCallsServiceAndReturnsSuccess() {
        doNothing().when(eventService).cancelEventAndRefund(TOKEN, EVENT_ID);

        assertInstanceOf(CompanyEventListPresenter.CancelOutcome.Success.class,
                presenter.cancelEvent(TOKEN, EVENT_ID));

        verify(eventService).cancelEventAndRefund(TOKEN, EVENT_ID);
    }

    @Test
    void GivenTheServiceFailsInternally_WhenCancelEvent_ThenReturnsGenericFailure() {
        doThrow(new RuntimeException("could not execute statement")).when(eventService).cancelEventAndRefund(TOKEN, EVENT_ID);

        CompanyEventListPresenter.CancelOutcome.Failure fail =
                assertInstanceOf(CompanyEventListPresenter.CancelOutcome.Failure.class,
                        presenter.cancelEvent(TOKEN, EVENT_ID));

        assertEquals(ServiceErrors.GENERIC_FAILURE_MESSAGE, fail.reason());
    }

    // UI-A3 (#187): the event is cancelled before its refunds run, so a refund failure is not a failed cancel.
    // A plain refund failure can mix declined receipts, which cancelling again retries, with pending ones,
    // which it does not; its own message says which, so the outcome promises no retry.
    @Test
    void GivenARefundOfTheCancelFails_WhenCancelEvent_ThenRefundFailedCarriesTheRefundFailure() {
        RefundFailedException refundFailure = new RefundFailedException(List.of(12), "not refunded for cancelled event 9 (declined: [12], "
                + "still pending: []); the other receipts were refunded, and cancelling again retries the declined ones");
        doThrow(refundFailure).when(eventService).cancelEventAndRefund(TOKEN, EVENT_ID);

        CompanyEventListPresenter.CancelOutcome.RefundFailed refundFailed =
                assertInstanceOf(CompanyEventListPresenter.CancelOutcome.RefundFailed.class,
                        presenter.cancelEvent(TOKEN, EVENT_ID));

        assertEquals(refundFailure.getMessage(), refundFailed.reason());
        assertFalse(refundFailed.cancelAgainRetries());
    }

    // No money moved and the claim is released, so cancelling again retries the refund.
    @Test
    void GivenTheGatewayIsUnreachableForARefundOfTheCancel_WhenCancelEvent_ThenRefundFailedSaysCancellingAgainRetriesIt() {
        RefundFailedException refundFailure = RefundGatewayUnreachableException.forPaymentTransaction(9001,
                "refund gateway unreachable: Connection refused", new RuntimeException("refused"));
        doThrow(refundFailure).when(eventService).cancelEventAndRefund(TOKEN, EVENT_ID);

        CompanyEventListPresenter.CancelOutcome.RefundFailed refundFailed =
                assertInstanceOf(CompanyEventListPresenter.CancelOutcome.RefundFailed.class,
                        presenter.cancelEvent(TOKEN, EVENT_ID));

        assertCarriesNoGatewayText(refundFailed.reason());
        assertTrue(refundFailed.cancelAgainRetries());
    }

    // The refund may have been made and stays pending for manual recovery; cancelling again skips it.
    @Test
    void GivenARefundOfTheCancelHasAnUnknownOutcome_WhenCancelEvent_ThenRefundFailedSaysCancellingAgainDoesNotRetryIt() {
        RefundFailedException refundFailure = RefundOutcomeUnknownException.forPaymentTransaction(9001,
                "no answer from the refund gateway: Read timed out", new RuntimeException("timeout"));
        doThrow(refundFailure).when(eventService).cancelEventAndRefund(TOKEN, EVENT_ID);

        CompanyEventListPresenter.CancelOutcome.RefundFailed refundFailed =
                assertInstanceOf(CompanyEventListPresenter.CancelOutcome.RefundFailed.class,
                        presenter.cancelEvent(TOKEN, EVENT_ID));

        assertCarriesNoGatewayText(refundFailed.reason());
        assertFalse(refundFailed.cancelAgainRetries());
    }

    // ── deleteEvent ───────────────────────────────────────────────────────────

    @Test
    void GivenANullToken_WhenDeleteEvent_ThenReturnsNotAuthenticatedWithoutCallingService() {
        assertInstanceOf(CompanyEventListPresenter.ActionOutcome.NotAuthenticated.class,
                presenter.deleteEvent(null, EVENT_ID));
        verifyNoInteractions(eventService);
    }

    @Test
    void GivenAnInvalidToken_WhenDeleteEvent_ThenReturnsNotAuthenticated() {
        doThrow(new InvalidTokenException("bad")).when(eventService).deleteEvent(TOKEN, EVENT_ID);

        assertInstanceOf(CompanyEventListPresenter.ActionOutcome.NotAuthenticated.class,
                presenter.deleteEvent(TOKEN, EVENT_ID));
    }

    @Test
    void GivenTheServiceAcceptsTheDelete_WhenDeleteEvent_ThenCallsServiceAndReturnsSuccess() {
        doNothing().when(eventService).deleteEvent(TOKEN, EVENT_ID);

        assertInstanceOf(CompanyEventListPresenter.ActionOutcome.Success.class,
                presenter.deleteEvent(TOKEN, EVENT_ID));

        verify(eventService).deleteEvent(TOKEN, EVENT_ID);
    }

    @Test
    void GivenAnEventNotCanceled_WhenDeleteEvent_ThenReturnsFailureWithMessage() {
        doThrow(new InvalidStateTransitionException("Only CANCELED events can be deleted"))
                .when(eventService).deleteEvent(TOKEN, EVENT_ID);

        CompanyEventListPresenter.ActionOutcome.Failure fail =
                assertInstanceOf(CompanyEventListPresenter.ActionOutcome.Failure.class,
                        presenter.deleteEvent(TOKEN, EVENT_ID));

        assertEquals("Only CANCELED events can be deleted", fail.reason());
    }

    @Test
    void GivenAnEventWithSalesHistory_WhenDeleteEvent_ThenReturnsFailureWithMessage() {
        doThrow(new BusinessRuleViolationException("Can't delete an event with sales history"))
                .when(eventService).deleteEvent(TOKEN, EVENT_ID);

        CompanyEventListPresenter.ActionOutcome.Failure fail =
                assertInstanceOf(CompanyEventListPresenter.ActionOutcome.Failure.class,
                        presenter.deleteEvent(TOKEN, EVENT_ID));

        assertEquals("Can't delete an event with sales history", fail.reason());
    }

    // ── changeEventStatus ─────────────────────────────────────────────────────

    @Test
    void GivenANullToken_WhenChangeEventStatus_ThenReturnsNotAuthenticatedWithoutCallingService() {
        assertInstanceOf(CompanyEventListPresenter.ActionOutcome.NotAuthenticated.class,
                presenter.changeEventStatus(null, EVENT_ID, EventStatus.ON_SALE));
        verifyNoInteractions(eventService);
    }

    @Test
    void GivenAnInvalidToken_WhenChangeEventStatus_ThenReturnsNotAuthenticated() {
        doThrow(new InvalidTokenException("bad"))
                .when(eventService).changeEventStatus(TOKEN, EVENT_ID, EventStatus.ON_SALE);

        assertInstanceOf(CompanyEventListPresenter.ActionOutcome.NotAuthenticated.class,
                presenter.changeEventStatus(TOKEN, EVENT_ID, EventStatus.ON_SALE));
    }

    @Test
    void GivenTheTargetStatusOnSale_WhenChangeEventStatus_ThenCallsServiceAndReturnsSuccess() {
        doNothing().when(eventService).changeEventStatus(TOKEN, EVENT_ID, EventStatus.ON_SALE);

        assertInstanceOf(CompanyEventListPresenter.ActionOutcome.Success.class,
                presenter.changeEventStatus(TOKEN, EVENT_ID, EventStatus.ON_SALE));

        verify(eventService).changeEventStatus(eq(TOKEN), eq(EVENT_ID), eq(EventStatus.ON_SALE));
    }

    @Test
    void GivenTheTargetStatusScheduled_WhenChangeEventStatus_ThenCallsServiceAndReturnsSuccess() {
        doNothing().when(eventService).changeEventStatus(TOKEN, EVENT_ID, EventStatus.SCHEDULED);

        assertInstanceOf(CompanyEventListPresenter.ActionOutcome.Success.class,
                presenter.changeEventStatus(TOKEN, EVENT_ID, EventStatus.SCHEDULED));

        verify(eventService).changeEventStatus(eq(TOKEN), eq(EVENT_ID), eq(EventStatus.SCHEDULED));
    }

    @Test
    void GivenTheServiceFailsInternally_WhenChangeEventStatus_ThenReturnsGenericFailure() {
        doThrow(new RuntimeException("could not execute statement"))
                .when(eventService).changeEventStatus(TOKEN, EVENT_ID, EventStatus.ON_SALE);

        CompanyEventListPresenter.ActionOutcome.Failure fail =
                assertInstanceOf(CompanyEventListPresenter.ActionOutcome.Failure.class,
                        presenter.changeEventStatus(TOKEN, EVENT_ID, EventStatus.ON_SALE));

        assertEquals(ServiceErrors.GENERIC_FAILURE_MESSAGE, fail.reason());
    }

    // ── countries / cities (owner-scope filter options) ───────────────────────

    @Test
    void GivenANullToken_WhenCountries_ThenIsEmptyWithoutCallingServices() {
        assertEquals(new ListOutcome.Loaded<>(List.of()), presenter.countries(null, null));
        verifyNoInteractions(eventService, companyService);
    }

    @Test
    void GivenNoCompany_WhenCountries_ThenIsEmptyWithoutCallingEventService() {
        when(companyService.findMyCompanies(TOKEN)).thenReturn(List.of());

        assertEquals(new ListOutcome.Loaded<>(List.of()), presenter.countries(TOKEN, null));
        verifyNoInteractions(eventService);
    }

    @Test
    void GivenCompanyEventsWithARepeatedCountry_WhenCountries_ThenReturnsThemDistinctAndSorted() {
        when(companyService.findMyCompanies(TOKEN)).thenReturn(List.of(company(COMPANY_ID)));
        when(eventService.listEventsForCompany(eq(TOKEN), eq(COMPANY_ID), any())).thenReturn(List.of(
                eventAt(1, "Israel", "Tel Aviv"),
                eventAt(2, "USA", "New York"),
                eventAt(3, "Israel", "Haifa")));

        assertEquals(new ListOutcome.Loaded<>(List.of("Israel", "USA")), presenter.countries(TOKEN, null));
    }

    @Test
    void GivenCompanyEventsInTwoCountries_WhenCities_ThenFiltersToTheCountryDistinctAndSorted() {
        when(companyService.findMyCompanies(TOKEN)).thenReturn(List.of(company(COMPANY_ID)));
        when(eventService.listEventsForCompany(eq(TOKEN), eq(COMPANY_ID), any())).thenReturn(List.of(
                eventAt(1, "Israel", "Tel Aviv"),
                eventAt(2, "Israel", "Haifa"),
                eventAt(4, "Israel", "Tel Aviv"),   // duplicate city
                eventAt(3, "USA", "New York")));

        assertEquals(new ListOutcome.Loaded<>(List.of("Haifa", "Tel Aviv")), presenter.cities(TOKEN, null, "Israel"));
    }

    @Test
    void GivenANullCountry_WhenCities_ThenIsEmpty() {
        assertEquals(new ListOutcome.Loaded<>(List.of()), presenter.cities(TOKEN, null, null));
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
                EventStatus.SCHEDULED, new ArrayList<>(), ZoneId.of("Asia/Jerusalem"), new ArrayList<>(), 0.0);
    }

    private static EventDetailDTO event(int id, String name) {
        return new EventDetailDTO(String.valueOf(id), name, null, null, null, null,
                String.valueOf(COMPANY_ID), "Company " + COMPANY_ID, EventStatus.SCHEDULED,
                new ArrayList<>(), ZoneId.of("Asia/Jerusalem"), new ArrayList<>(), 0.0);
    }

    // The payment gateway's own wording, and the charge it names, are operator text: the owner reads a
    // fixed reason instead (#187).
    private static void assertCarriesNoGatewayText(String reason) {
        assertFalse(reason.isBlank(), "the owner must be told why");
        for (String operatorText : List.of("gateway", "Connection refused", "Read timed out", "payment transaction", "9001")) {
            assertFalse(reason.contains(operatorText), "the reason shows operator text '" + operatorText + "': " + reason);
        }
    }

    // ---- an expired session reads as a dead one, as a revoked token does (ADR-025, #189) ----

    @Test
    void GivenAnExpiredSession_WhenLoad_ThenReturnsNotAuthenticated() {
        when(companyService.findMyCompanies(TOKEN)).thenThrow(new SessionExpiredException());

        assertInstanceOf(CompanyEventListPresenter.Outcome.NotAuthenticated.class,
                presenter.load(TOKEN, null, CatalogSearchFiltersDTO.empty()));
    }
}
