package com.ticketing.system.unit.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
import com.ticketing.system.shared.exception.InvalidTokenException;
import com.ticketing.system.ui.presenters.company.EventManagementPresenter;

/**
 * Unit tests for {@link EventManagementPresenter#create}. The create flow assembles an
 * {@link EventCreationDTO} (resolving the owning company) and delegates to
 * {@code EventManagementService.addEvent}; these tests pin the company-resolution rule and the
 * exception → {@code CreateOutcome} mapping without a UI or Spring context.
 */
class EventManagementPresenterTest {

    private static final String TOKEN = "owner-token";
    private static final ZoneId JERUSALEM = ZoneId.of("Asia/Jerusalem");

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
        return LocalDateTime.now().plusDays(1);
    }

    private static LocalDateTime futureEnd() {
        return LocalDateTime.now().plusDays(1).plusHours(3);
    }

    private EventManagementPresenter.CreateOutcome createWith(Integer preferredCompanyId,
                                                             LocalDateTime starts, LocalDateTime ends) {
        return presenter.create(TOKEN, preferredCompanyId, "Gala Night", "A great show",
                EventCategory.COMEDY.name(), "Israel", "Tel Aviv", starts, ends, JERUSALEM, List.of("Headliner"), 4.5);
    }

    @Test
    void create_success_returnsNewEventId() {
        when(companyService.findMyCompanies(TOKEN)).thenReturn(List.of(company(1)));
        when(eventService.addEvent(eq(TOKEN), any())).thenReturn(detailWithId("5"));

        EventManagementPresenter.CreateOutcome outcome = createWith(null, futureStart(), futureEnd());

        EventManagementPresenter.CreateOutcome.Success ok =
                assertInstanceOf(EventManagementPresenter.CreateOutcome.Success.class, outcome);
        assertEquals("5", ok.eventId());
    }

    @Test
    void create_prefersSelectedCompanyWhenOwned() {
        when(companyService.findMyCompanies(TOKEN)).thenReturn(List.of(company(1), company(2)));
        when(eventService.addEvent(eq(TOKEN), any())).thenReturn(detailWithId("9"));

        createWith(2, futureStart(), futureEnd());

        ArgumentCaptor<EventCreationDTO> captor = ArgumentCaptor.forClass(EventCreationDTO.class);
        verify(eventService).addEvent(eq(TOKEN), captor.capture());
        assertEquals(2, captor.getValue().companyId());
    }

    @Test
    void create_preferredNotOwned_returnsNoCompany() {
        // A selected company the caller doesn't own must not silently retarget to another company.
        when(companyService.findMyCompanies(TOKEN)).thenReturn(List.of(company(1), company(2)));

        assertInstanceOf(EventManagementPresenter.CreateOutcome.NoCompany.class,
                createWith(99, futureStart(), futureEnd()));
        verifyNoInteractions(eventService);
    }

    @Test
    void create_noOwnedCompany_returnsNoCompany() {
        when(companyService.findMyCompanies(TOKEN)).thenReturn(List.of());

        assertInstanceOf(EventManagementPresenter.CreateOutcome.NoCompany.class,
                createWith(null, futureStart(), futureEnd()));
        verifyNoInteractions(eventService);
    }

    @Test
    void create_nullToken_returnsNotAuthenticated() {
        EventManagementPresenter.CreateOutcome outcome = presenter.create(null, 1, "Gala", "d",
                EventCategory.COMEDY.name(), "Israel", "Tel Aviv", futureStart(), futureEnd(),
                JERUSALEM,
                List.of("Headliner"), 4.5);

        assertInstanceOf(EventManagementPresenter.CreateOutcome.NotAuthenticated.class, outcome);
        verifyNoInteractions(companyService, eventService);
    }

    @Test
    void create_invalidToken_returnsNotAuthenticated() {
        when(companyService.findMyCompanies(TOKEN)).thenReturn(List.of(company(1)));
        when(eventService.addEvent(eq(TOKEN), any())).thenThrow(new InvalidTokenException());

        assertInstanceOf(EventManagementPresenter.CreateOutcome.NotAuthenticated.class,
                createWith(null, futureStart(), futureEnd()));
    }

    @Test
    void create_blankCategory_returnsInvalidInput() {
        // Required inputs are validated before any service call, so a null category maps to
        // InvalidInput (not a leaked NPE → Failure) and touches neither service.
        EventManagementPresenter.CreateOutcome outcome = presenter.create(TOKEN, 1, "Gala", "desc",
                null, "Israel", "Tel Aviv", futureStart(), futureEnd(), JERUSALEM, List.of("Headliner"), 4.5);

        assertInstanceOf(EventManagementPresenter.CreateOutcome.InvalidInput.class, outcome);
        verifyNoInteractions(companyService, eventService);
    }

    @Test
    void create_ratingOutOfRange_returnsInvalidInput() {
        // Rating is validated up front (0–5), so a bad value maps to InvalidInput and touches no service.
        EventManagementPresenter.CreateOutcome outcome = presenter.create(TOKEN, 1, "Gala", "desc",
                EventCategory.COMEDY.name(), "Israel", "Tel Aviv", futureStart(), futureEnd(),
                JERUSALEM,
                List.of("Headliner"), 6.0);

        assertInstanceOf(EventManagementPresenter.CreateOutcome.InvalidInput.class, outcome);
        verifyNoInteractions(companyService, eventService);
    }

    @Test
    void create_nullRating_returnsInvalidInput() {
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
                createWith(null, LocalDateTime.now().minusDays(1), futureEnd());

        EventManagementPresenter.CreateOutcome.InvalidInput invalid =
                assertInstanceOf(EventManagementPresenter.CreateOutcome.InvalidInput.class, outcome);
        assertEquals("Start time must be in the future", invalid.reason());
    }

    @Test
    void create_serviceFailure_returnsFailure() {
        when(companyService.findMyCompanies(TOKEN)).thenReturn(List.of(company(1)));
        when(eventService.addEvent(eq(TOKEN), any())).thenThrow(new RuntimeException("boom"));

        assertInstanceOf(EventManagementPresenter.CreateOutcome.Failure.class,
                createWith(null, futureStart(), futureEnd()));
    }

    private static EventDetailDTO detailWithId(String id) {
        return new EventDetailDTO(id, "Gala Night", null, "A great show", EventCategory.COMEDY,
                new Location("Israel", "Tel Aviv"), "1", "Company 1", EventStatus.DRAFT,
                List.of(), JERUSALEM, List.of("Headliner"), 0.0);
    }
}
