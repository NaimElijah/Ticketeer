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
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.ticketing.system.Core.Application.dto.EventCreationDTO;
import com.ticketing.system.Core.Application.dto.EventDetailDTO;
import com.ticketing.system.Core.Application.dto.MyCompanyDTO;
import com.ticketing.system.organization.application.service.CompanyManagementService;
import com.ticketing.system.catalog.application.service.EventManagementService;
import com.ticketing.system.catalog.domain.EventCategory;
import com.ticketing.system.catalog.domain.EventStatus;
import com.ticketing.system.catalog.domain.Location;
import com.ticketing.system.shared.exception.InvalidTokenException;
import com.ticketing.system.Presentation.presenters.company.EventManagementPresenter;

/**
 * Unit tests for {@link EventManagementPresenter#create}. The create flow assembles an
 * {@link EventCreationDTO} (resolving the owning company) and delegates to
 * {@code EventManagementService.addEvent}; these tests pin the company-resolution rule and the
 * exception → {@code CreateOutcome} mapping without a UI or Spring context.
 */
class EventManagementPresenterTest {

    private static final String TOKEN = "owner-token";

    private EventManagementService eventService;
    private CompanyManagementService companyService;
    private EventManagementPresenter presenter;

    @BeforeEach
    void setUp() {
        eventService = mock(EventManagementService.class);
        companyService = mock(CompanyManagementService.class);
        presenter = new EventManagementPresenter(eventService, companyService);
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
                EventCategory.COMEDY.name(), "Israel", "Tel Aviv", starts, ends, List.of("Headliner"), 4.5);
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
                null, "Israel", "Tel Aviv", futureStart(), futureEnd(), List.of("Headliner"), 4.5);

        assertInstanceOf(EventManagementPresenter.CreateOutcome.InvalidInput.class, outcome);
        verifyNoInteractions(companyService, eventService);
    }

    @Test
    void create_ratingOutOfRange_returnsInvalidInput() {
        // Rating is validated up front (0–5), so a bad value maps to InvalidInput and touches no service.
        EventManagementPresenter.CreateOutcome outcome = presenter.create(TOKEN, 1, "Gala", "desc",
                EventCategory.COMEDY.name(), "Israel", "Tel Aviv", futureStart(), futureEnd(),
                List.of("Headliner"), 6.0);

        assertInstanceOf(EventManagementPresenter.CreateOutcome.InvalidInput.class, outcome);
        verifyNoInteractions(companyService, eventService);
    }

    @Test
    void create_nullRating_returnsInvalidInput() {
        // Rating is required — a null rating maps to InvalidInput and touches no service.
        EventManagementPresenter.CreateOutcome outcome = presenter.create(TOKEN, 1, "Gala", "desc",
                EventCategory.COMEDY.name(), "Israel", "Tel Aviv", futureStart(), futureEnd(),
                List.of("Headliner"), null);

        assertInstanceOf(EventManagementPresenter.CreateOutcome.InvalidInput.class, outcome);
        verifyNoInteractions(companyService, eventService);
    }

    @Test
    void create_pastShowDate_returnsInvalidInput() {
        when(companyService.findMyCompanies(TOKEN)).thenReturn(List.of(company(1)));

        // A past start makes the domain ShowDate constructor reject the request before addEvent.
        EventManagementPresenter.CreateOutcome outcome =
                createWith(null, LocalDateTime.now().minusDays(1), futureEnd());

        assertInstanceOf(EventManagementPresenter.CreateOutcome.InvalidInput.class, outcome);
        verifyNoInteractions(eventService);
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
                List.of(), List.of("Headliner"), 0.0);
    }
}
