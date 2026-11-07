package com.ticketing.system.unit.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.ticketing.system.catalog.application.dto.EventCreationDTO;
import com.ticketing.system.catalog.application.dto.EventDetailDTO;
import com.ticketing.system.shared.dto.MyCompanyDTO;
import com.ticketing.system.organization.application.service.CompanyManagementService;
import com.ticketing.system.catalog.application.service.EventManagementService;
import com.ticketing.system.catalog.domain.EventCategory;
import com.ticketing.system.catalog.domain.EventStatus;
import com.ticketing.system.catalog.domain.Location;
import com.ticketing.system.shared.config.PlatformTimeProperties;
import com.ticketing.system.shared.exception.BusinessRuleViolationException;
import com.ticketing.system.shared.exception.InvalidTokenException;
import com.ticketing.system.shared.exception.RefundFailedException;
import com.ticketing.system.shared.exception.RefundGatewayUnreachableException;
import com.ticketing.system.shared.exception.RefundOutcomeUnknownException;
import com.ticketing.system.ui.presenters.company.EventManagementPresenter;
import com.ticketing.system.ui.support.ServiceErrors;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.anyString;
import com.ticketing.system.shared.exception.EventNotFoundException;
import com.ticketing.system.shared.exception.SessionExpiredException;
import com.ticketing.system.shared.exception.UnauthorizedActionException;
import com.ticketing.system.shared.dto.EventUpdateDTO;
import com.ticketing.system.shared.dto.LocationDTO;
import jakarta.persistence.PersistenceException;

/**
 * Unit tests for {@link EventManagementPresenter#create}. The create flow assembles an
 * {@link EventCreationDTO} (resolving the owning company) and delegates to
 * {@code EventManagementService.addEvent}; these tests pin the company-resolution rule and the
 * exception → {@code CreateOutcome} mapping without a UI or Spring context. They also pin
 * {@link EventManagementPresenter#cancel}'s exception → {@code CancelOutcome} mapping.
 */
class EventManagementPresenterTest {

    private static final String TOKEN = "owner-token";
    private static final int EVENT_ID = 9;
    private static final ZoneId JERUSALEM = ZoneId.of("Asia/Jerusalem");
    // The service is a mock and the presenter reads no clock: the dates are only passed through.
    private static final LocalDateTime NOW = LocalDateTime.of(2027, 7, 15, 17, 0);

    private EventManagementService eventService;
    private CompanyManagementService companyService;
    private EventManagementPresenter presenter;

    @BeforeEach
    void setUp() {
        eventService = mock(EventManagementService.class);
        companyService = mock(CompanyManagementService.class);
        presenter = new EventManagementPresenter(eventService, companyService,
                new PlatformTimeProperties(JERUSALEM));
    }

    private static MyCompanyDTO company(int id) {
        return new MyCompanyDTO(id, "Company " + id, "Co-owner");
    }

    private static LocalDateTime futureStart() {
        return NOW.plusDays(1);
    }

    private static LocalDateTime futureEnd() {
        return NOW.plusDays(1).plusHours(3);
    }

    private EventManagementPresenter.CreateOutcome createWith(Integer preferredCompanyId,
                                                             LocalDateTime starts, LocalDateTime ends) {
        return presenter.create(TOKEN, preferredCompanyId, "Gala Night", "A great show",
                EventCategory.COMEDY.name(), "Israel", "Tel Aviv", starts, ends, JERUSALEM, List.of("Headliner"), 4.5);
    }

    @Test
    void GivenOneOwnedCompany_WhenCreate_ThenReturnsNewEventId() {
        when(companyService.findMyCompanies(TOKEN)).thenReturn(List.of(company(1)));
        when(eventService.addEvent(eq(TOKEN), any())).thenReturn(detailWithId("5"));

        EventManagementPresenter.CreateOutcome outcome = createWith(null, futureStart(), futureEnd());

        EventManagementPresenter.CreateOutcome.Success ok =
                assertInstanceOf(EventManagementPresenter.CreateOutcome.Success.class, outcome);
        assertEquals("5", ok.eventId());
    }

    @Test
    void GivenASelectedCompanyThatIsOwned_WhenCreate_ThenPrefersTheSelectedCompany() {
        when(companyService.findMyCompanies(TOKEN)).thenReturn(List.of(company(1), company(2)));
        when(eventService.addEvent(eq(TOKEN), any())).thenReturn(detailWithId("9"));

        createWith(2, futureStart(), futureEnd());

        ArgumentCaptor<EventCreationDTO> captor = ArgumentCaptor.forClass(EventCreationDTO.class);
        verify(eventService).addEvent(eq(TOKEN), captor.capture());
        assertEquals(2, captor.getValue().companyId());
    }

    @Test
    void GivenAPreferredCompanyNotOwned_WhenCreate_ThenReturnsChooseCompanyAndNothingCreated() {
        // A selected company the caller doesn't own must not silently retarget to another company.
        when(companyService.findMyCompanies(TOKEN)).thenReturn(List.of(company(1), company(2)));

        assertInstanceOf(EventManagementPresenter.CreateOutcome.ChooseCompany.class,
                createWith(99, futureStart(), futureEnd()));
        verifyNoInteractions(eventService);
    }

    // The caller's companies are unordered, so the first can be any of them and is never a default.
    @Test
    void GivenTwoCompaniesAndNoSelection_WhenCreate_ThenChooseCompanyAndNothingCreated() {
        when(companyService.findMyCompanies(TOKEN)).thenReturn(List.of(company(1), company(2)));

        assertInstanceOf(EventManagementPresenter.CreateOutcome.ChooseCompany.class,
                createWith(null, futureStart(), futureEnd()));
        verifyNoInteractions(eventService);
    }

    @Test
    void GivenNoOwnedCompany_WhenCreate_ThenReturnsNoCompanyAndNothingCreated() {
        when(companyService.findMyCompanies(TOKEN)).thenReturn(List.of());

        assertInstanceOf(EventManagementPresenter.CreateOutcome.NoCompany.class,
                createWith(null, futureStart(), futureEnd()));
        verifyNoInteractions(eventService);
    }

    @Test
    void GivenANullToken_WhenCreate_ThenReturnsNotAuthenticatedAndNoServiceCall() {
        EventManagementPresenter.CreateOutcome outcome = presenter.create(null, 1, "Gala", "d",
                EventCategory.COMEDY.name(), "Israel", "Tel Aviv", futureStart(), futureEnd(),
                JERUSALEM,
                List.of("Headliner"), 4.5);

        assertInstanceOf(EventManagementPresenter.CreateOutcome.NotAuthenticated.class, outcome);
        verifyNoInteractions(companyService, eventService);
    }

    @Test
    void GivenAnInvalidToken_WhenCreate_ThenReturnsNotAuthenticated() {
        when(companyService.findMyCompanies(TOKEN)).thenReturn(List.of(company(1)));
        when(eventService.addEvent(eq(TOKEN), any())).thenThrow(new InvalidTokenException());

        assertInstanceOf(EventManagementPresenter.CreateOutcome.NotAuthenticated.class,
                createWith(null, futureStart(), futureEnd()));
    }

    @Test
    void GivenANullCategory_WhenCreate_ThenReturnsInvalidInputAndNoServiceCall() {
        // Required inputs are validated before any service call, so a null category maps to
        // InvalidInput (not a leaked NPE → Failure) and touches neither service.
        EventManagementPresenter.CreateOutcome outcome = presenter.create(TOKEN, 1, "Gala", "desc",
                null, "Israel", "Tel Aviv", futureStart(), futureEnd(), JERUSALEM, List.of("Headliner"), 4.5);

        assertInstanceOf(EventManagementPresenter.CreateOutcome.InvalidInput.class, outcome);
        verifyNoInteractions(companyService, eventService);
    }

    @Test
    void GivenARatingOutOfRange_WhenCreate_ThenReturnsInvalidInputAndNoServiceCall() {
        // Rating is validated up front (0–5), so a bad value maps to InvalidInput and touches no service.
        EventManagementPresenter.CreateOutcome outcome = presenter.create(TOKEN, 1, "Gala", "desc",
                EventCategory.COMEDY.name(), "Israel", "Tel Aviv", futureStart(), futureEnd(),
                JERUSALEM,
                List.of("Headliner"), 6.0);

        assertInstanceOf(EventManagementPresenter.CreateOutcome.InvalidInput.class, outcome);
        verifyNoInteractions(companyService, eventService);
    }

    @Test
    void GivenANullRating_WhenCreate_ThenReturnsInvalidInputAndNoServiceCall() {
        // Rating is required — a null rating maps to InvalidInput and touches no service.
        EventManagementPresenter.CreateOutcome outcome = presenter.create(TOKEN, 1, "Gala", "desc",
                EventCategory.COMEDY.name(), "Israel", "Tel Aviv", futureStart(), futureEnd(),
                JERUSALEM,
                List.of("Headliner"), null);

        assertInstanceOf(EventManagementPresenter.CreateOutcome.InvalidInput.class, outcome);
        verifyNoInteractions(companyService, eventService);
    }

    @Test
    void GivenNoTimeZone_WhenCreate_ThenInvalidInputAndNoServiceCall() {
        EventManagementPresenter.CreateOutcome outcome = presenter.create(TOKEN, 1, "Gala", "desc",
                EventCategory.COMEDY.name(), "Israel", "Tel Aviv", futureStart(), futureEnd(), null,
                List.of("Headliner"), 4.5);

        assertInstanceOf(EventManagementPresenter.CreateOutcome.InvalidInput.class, outcome);
        verifyNoInteractions(companyService, eventService);
    }

    @Test
    void GivenChosenTimeZone_WhenCreate_ThenTheRequestCarriesIt() {
        when(companyService.findMyCompanies(TOKEN)).thenReturn(List.of(company(1)));
        when(eventService.addEvent(eq(TOKEN), any())).thenReturn(detailWithId("5"));
        ZoneId london = ZoneId.of("Europe/London");

        presenter.create(TOKEN, null, "Gala", "desc", EventCategory.COMEDY.name(), "UK", "London",
                futureStart(), futureEnd(), london, List.of("Headliner"), 4.5);

        ArgumentCaptor<EventCreationDTO> request = ArgumentCaptor.forClass(EventCreationDTO.class);
        verify(eventService).addEvent(eq(TOKEN), request.capture());
        assertEquals(london, request.getValue().timeZone());
    }

    @Test
    void GivenPlatformZone_WhenDefaultTimeZone_ThenItIsThePlatformZone() {
        assertEquals(JERUSALEM, presenter.defaultTimeZone());
    }

    // Whether a start is in the past depends on the venue's zone, so the service judges it with the
    // injected clock (ADR-008); the presenter only maps the rejection to InvalidInput with its reason.
    @Test
    void GivenServiceRejectsAPastStart_WhenCreate_ThenInvalidInputWithTheReason() {
        when(companyService.findMyCompanies(TOKEN)).thenReturn(List.of(company(1)));
        when(eventService.addEvent(eq(TOKEN), any()))
                .thenThrow(new IllegalArgumentException("Start time must be in the future"));

        EventManagementPresenter.CreateOutcome outcome =
                createWith(null, NOW.minusDays(1), futureEnd());

        EventManagementPresenter.CreateOutcome.InvalidInput invalid =
                assertInstanceOf(EventManagementPresenter.CreateOutcome.InvalidInput.class, outcome);
        assertEquals("Start time must be in the future", invalid.reason());
    }

    // The view shows the InvalidInput reason, so it must pass the same filter as every other reason.
    @Test
    void GivenTheServiceRefusesWithoutAMessage_WhenCreate_ThenInvalidInputCarriesTheGenericText() {
        when(companyService.findMyCompanies(TOKEN)).thenReturn(List.of(company(1)));
        when(eventService.addEvent(eq(TOKEN), any())).thenThrow(new IllegalStateException());

        EventManagementPresenter.CreateOutcome outcome = createWith(null, futureStart(), futureEnd());

        EventManagementPresenter.CreateOutcome.InvalidInput invalid =
                assertInstanceOf(EventManagementPresenter.CreateOutcome.InvalidInput.class, outcome);
        assertEquals(ServiceErrors.GENERIC_FAILURE_MESSAGE, invalid.reason());
    }

    // The domain bounds the name to its column (#186); the view shows this reason on Failure.
    @Test
    void GivenTheDomainRefusesTheName_WhenCreate_ThenFailureCarriesTheDomainReason() {
        String reason = "Event name can be at most 255 characters.";
        when(companyService.findMyCompanies(TOKEN)).thenReturn(List.of(company(1)));
        when(eventService.addEvent(eq(TOKEN), any())).thenThrow(new BusinessRuleViolationException(reason));

        EventManagementPresenter.CreateOutcome outcome = createWith(null, futureStart(), futureEnd());

        EventManagementPresenter.CreateOutcome.Failure failure =
                assertInstanceOf(EventManagementPresenter.CreateOutcome.Failure.class, outcome);
        assertEquals(reason, failure.reason());
    }

    @Test
    void GivenTheServiceFails_WhenCreate_ThenReturnsFailure() {
        when(companyService.findMyCompanies(TOKEN)).thenReturn(List.of(company(1)));
        when(eventService.addEvent(eq(TOKEN), any())).thenThrow(new RuntimeException("boom"));

        assertInstanceOf(EventManagementPresenter.CreateOutcome.Failure.class,
                createWith(null, futureStart(), futureEnd()));
    }

    // UI-A3 (#187): the event is cancelled before its refunds run, so a refund failure is not a failed cancel.
    // A plain refund failure can mix declined receipts, which cancelling again retries, with pending ones,
    // which it does not; its own message says which, so the outcome promises no retry.
    @Test
    void GivenARefundOfTheCancelFails_WhenCancel_ThenRefundFailedCarriesTheRefundFailure() {
        RefundFailedException refundFailure = new RefundFailedException(List.of(12), "not refunded for cancelled event 9 (declined: [12], "
                + "still pending: []); the other receipts were refunded, and cancelling again retries the declined ones");
        doThrow(refundFailure).when(eventService).cancelEventAndRefund(TOKEN, EVENT_ID);

        EventManagementPresenter.CancelOutcome.RefundFailed refundFailed =
                assertInstanceOf(EventManagementPresenter.CancelOutcome.RefundFailed.class,
                        presenter.cancel(TOKEN, EVENT_ID));

        assertEquals(refundFailure.getMessage(), refundFailed.reason());
        assertFalse(refundFailed.cancelAgainRetries());
    }

    // No money moved and the claim is released, so cancelling again retries the refund.
    @Test
    void GivenTheGatewayIsUnreachableForARefundOfTheCancel_WhenCancel_ThenRefundFailedSaysCancellingAgainRetriesIt() {
        RefundFailedException refundFailure = RefundGatewayUnreachableException.forPaymentTransaction(9001,
                "refund gateway unreachable: Connection refused", new RuntimeException("refused"));
        doThrow(refundFailure).when(eventService).cancelEventAndRefund(TOKEN, EVENT_ID);

        EventManagementPresenter.CancelOutcome.RefundFailed refundFailed =
                assertInstanceOf(EventManagementPresenter.CancelOutcome.RefundFailed.class,
                        presenter.cancel(TOKEN, EVENT_ID));

        assertCarriesNoGatewayText(refundFailed.reason());
        assertTrue(refundFailed.cancelAgainRetries());
    }

    // The refund may have been made and stays pending for manual recovery; cancelling again skips it.
    @Test
    void GivenARefundOfTheCancelHasAnUnknownOutcome_WhenCancel_ThenRefundFailedSaysCancellingAgainDoesNotRetryIt() {
        RefundFailedException refundFailure = RefundOutcomeUnknownException.forPaymentTransaction(9001,
                "no answer from the refund gateway: Read timed out", new RuntimeException("timeout"));
        doThrow(refundFailure).when(eventService).cancelEventAndRefund(TOKEN, EVENT_ID);

        EventManagementPresenter.CancelOutcome.RefundFailed refundFailed =
                assertInstanceOf(EventManagementPresenter.CancelOutcome.RefundFailed.class,
                        presenter.cancel(TOKEN, EVENT_ID));

        assertCarriesNoGatewayText(refundFailed.reason());
        assertFalse(refundFailed.cancelAgainRetries());
    }

    @Test
    void GivenCancellingFailsForAnotherReason_WhenCancel_ThenFailureCarriesThatReason() {
        String reason = "You lack the permission to cancel this event.";
        doThrow(new BusinessRuleViolationException(reason)).when(eventService).cancelEventAndRefund(TOKEN, EVENT_ID);

        EventManagementPresenter.CancelOutcome.Failure failure =
                assertInstanceOf(EventManagementPresenter.CancelOutcome.Failure.class,
                        presenter.cancel(TOKEN, EVENT_ID));

        assertEquals(reason, failure.reason());
    }

    private static EventDetailDTO detailWithId(String id) {
        return new EventDetailDTO(id, "Gala Night", null, "A great show", EventCategory.COMEDY,
                new Location("Israel", "Tel Aviv"), "1", "Company 1", EventStatus.DRAFT,
                List.of(), JERUSALEM, List.of("Headliner"), 0.0);
    }

    // The payment gateway's own wording, and the charge it names, are operator text: the owner reads a
    // fixed reason instead (#187).
    private static void assertCarriesNoGatewayText(String reason) {
        assertFalse(reason.isBlank(), "the owner must be told why");
        for (String operatorText : List.of("gateway", "Connection refused", "Read timed out", "payment transaction", "9001")) {
            assertFalse(reason.contains(operatorText), "the reason shows operator text '" + operatorText + "': " + reason);
        }
    }

    // ---- load, save and the cancel's other outcomes (#189) ----

    private static final EventUpdateDTO RENAME = new EventUpdateDTO(String.valueOf(EVENT_ID), "Gala Night II", null,
            null, new LocationDTO("Israel", "Haifa"), null, null);

    @Test
    void GivenANullToken_WhenLoad_ThenNotAuthenticatedAndNoServiceCall() {
        assertEquals(new EventManagementPresenter.LoadOutcome.NotAuthenticated(), presenter.load(null, EVENT_ID));
        verifyNoInteractions(eventService, companyService);
    }

    @Test
    void GivenTheEvent_WhenLoad_ThenSuccessCarriesItsDetail() {
        EventDetailDTO detail = detailWithId(String.valueOf(EVENT_ID));
        when(eventService.getEventDetail(TOKEN, EVENT_ID)).thenReturn(detail);

        assertEquals(new EventManagementPresenter.LoadOutcome.Success(detail), presenter.load(TOKEN, EVENT_ID));
    }

    @Test
    void GivenARevokedToken_WhenLoad_ThenNotAuthenticated() {
        when(eventService.getEventDetail(TOKEN, EVENT_ID)).thenThrow(new InvalidTokenException("revoked"));

        assertEquals(new EventManagementPresenter.LoadOutcome.NotAuthenticated(), presenter.load(TOKEN, EVENT_ID));
    }

    @Test
    void GivenAnExpiredSession_WhenLoad_ThenNotAuthenticated() {
        when(eventService.getEventDetail(TOKEN, EVENT_ID)).thenThrow(new SessionExpiredException());

        assertEquals(new EventManagementPresenter.LoadOutcome.NotAuthenticated(), presenter.load(TOKEN, EVENT_ID));
    }

    @Test
    void GivenAMissingEvent_WhenLoad_ThenNotFound() {
        when(eventService.getEventDetail(TOKEN, EVENT_ID)).thenThrow(new EventNotFoundException(EVENT_ID));

        assertEquals(new EventManagementPresenter.LoadOutcome.NotFound(), presenter.load(TOKEN, EVENT_ID));
    }

    @Test
    void GivenTheServiceRefusesTheCaller_WhenLoad_ThenFailureCarriesTheRule() {
        UnauthorizedActionException refusal = new UnauthorizedActionException("view event " + EVENT_ID);
        when(eventService.getEventDetail(TOKEN, EVENT_ID)).thenThrow(refusal);

        assertEquals(new EventManagementPresenter.LoadOutcome.Failure(refusal.getMessage()),
                presenter.load(TOKEN, EVENT_ID));
    }

    @Test
    void GivenAnInternalFailure_WhenLoad_ThenFailureCarriesTheGenericText() {
        when(eventService.getEventDetail(TOKEN, EVENT_ID))
                .thenThrow(new PersistenceException("could not prepare statement; table [events]"));

        assertEquals(new EventManagementPresenter.LoadOutcome.Failure(ServiceErrors.GENERIC_FAILURE_MESSAGE),
                presenter.load(TOKEN, EVENT_ID));
    }

    @Test
    void GivenANullToken_WhenSave_ThenNotAuthenticatedAndNoServiceCall() {
        assertEquals(new EventManagementPresenter.SaveOutcome.NotAuthenticated(), presenter.save(null, RENAME));
        verifyNoInteractions(eventService, companyService);
    }

    @Test
    void GivenAnEdit_WhenSave_ThenTheServiceGetsItUnchangedAndSuccess() {
        assertEquals(new EventManagementPresenter.SaveOutcome.Success(), presenter.save(TOKEN, RENAME));

        verify(eventService).editEventDetails(TOKEN, RENAME);
    }

    @Test
    void GivenARevokedToken_WhenSave_ThenNotAuthenticated() {
        doThrow(new InvalidTokenException("revoked")).when(eventService).editEventDetails(TOKEN, RENAME);

        assertEquals(new EventManagementPresenter.SaveOutcome.NotAuthenticated(), presenter.save(TOKEN, RENAME));
    }

    @Test
    void GivenAnExpiredSession_WhenSave_ThenNotAuthenticated() {
        doThrow(new SessionExpiredException()).when(eventService).editEventDetails(TOKEN, RENAME);

        assertEquals(new EventManagementPresenter.SaveOutcome.NotAuthenticated(), presenter.save(TOKEN, RENAME));
    }

    @Test
    void GivenTheEventRefusesTheEdit_WhenSave_ThenFailureCarriesTheRule() {
        BusinessRuleViolationException rule = new BusinessRuleViolationException("An event on sale cannot be renamed.");
        doThrow(rule).when(eventService).editEventDetails(TOKEN, RENAME);

        assertEquals(new EventManagementPresenter.SaveOutcome.Failure(rule.getMessage()), presenter.save(TOKEN, RENAME));
    }

    @Test
    void GivenAnInternalFailure_WhenSave_ThenFailureCarriesTheGenericText() {
        doThrow(new PersistenceException("could not execute statement [update events set name=?]"))
                .when(eventService).editEventDetails(TOKEN, RENAME);

        assertEquals(new EventManagementPresenter.SaveOutcome.Failure(ServiceErrors.GENERIC_FAILURE_MESSAGE),
                presenter.save(TOKEN, RENAME));
    }

    @Test
    void GivenANullToken_WhenCancel_ThenNotAuthenticatedAndNothingIsCancelled() {
        assertEquals(new EventManagementPresenter.CancelOutcome.NotAuthenticated(), presenter.cancel(null, EVENT_ID));
        verifyNoInteractions(eventService);
    }

    @Test
    void GivenARevokedToken_WhenCancel_ThenNotAuthenticated() {
        doThrow(new InvalidTokenException("revoked")).when(eventService).cancelEventAndRefund(TOKEN, EVENT_ID);

        assertEquals(new EventManagementPresenter.CancelOutcome.NotAuthenticated(), presenter.cancel(TOKEN, EVENT_ID));
    }

    @Test
    void GivenAnExpiredSession_WhenCancel_ThenNotAuthenticated() {
        doThrow(new SessionExpiredException()).when(eventService).cancelEventAndRefund(TOKEN, EVENT_ID);

        assertEquals(new EventManagementPresenter.CancelOutcome.NotAuthenticated(), presenter.cancel(TOKEN, EVENT_ID));
    }

    @Test
    void GivenTheCancelAndItsRefundsSucceed_WhenCancel_ThenSuccessAndTheEventIsCancelledOnce() {
        assertEquals(new EventManagementPresenter.CancelOutcome.Success(), presenter.cancel(TOKEN, EVENT_ID));

        verify(eventService).cancelEventAndRefund(TOKEN, EVENT_ID);
    }

    @Test
    void GivenARevokedTokenWhenListingCompanies_WhenCreate_ThenNotAuthenticatedAndNothingCreated() {
        when(companyService.findMyCompanies(TOKEN)).thenThrow(new InvalidTokenException("revoked"));

        assertEquals(new EventManagementPresenter.CreateOutcome.NotAuthenticated(),
                createWith(null, futureStart(), futureEnd()));
        verify(eventService, never()).addEvent(anyString(), any());
    }
}
