package com.ticketing.system.unit.presentation;

import com.ticketing.system.Core.Application.dto.EventDetailDTO;
import com.ticketing.system.Core.Application.dto.VenueMapConfigDTO;
import com.ticketing.system.Core.Application.services.EventManagementService;
import com.ticketing.system.Core.Domain.events.EventStatus;
import com.ticketing.system.shared.exception.InvalidTokenException;
import com.ticketing.system.Presentation.presenters.company.VenueMapPresenter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit guard for {@code VenueMapPresenter.saveMap}: the venue map is persisted to the
 * <em>event's own</em> company (resolved via {@code getEventDetail(...).companyId()}), not the
 * caller's "first owned company". This is what lets a manager granted {@code CONFIGURE_VENUE} —
 * who has no Owner appointment — save a layout edit (#429), and what targets the correct company
 * for a multi-company owner.
 */
class VenueMapPresenterTest {

    private static final String TOKEN = "manager-token";
    private static final int EVENT_ID = 7;
    private static final int COMPANY_ID = 42;

    private EventManagementService eventService;
    private VenueMapPresenter presenter;

    @BeforeEach
    void setUp() {
        eventService = mock(EventManagementService.class);
        presenter = new VenueMapPresenter(eventService);
    }

    @Test
    void saveMap_nullToken_returnsNotAuthenticatedWithoutTouchingService() {
        assertInstanceOf(VenueMapPresenter.SaveOutcome.NotAuthenticated.class,
                presenter.saveMap(null, String.valueOf(EVENT_ID), config()));
        verifyNoInteractions(eventService);
    }

    @Test
    void saveMap_resolvesCompanyFromEvent_thenConfiguresAndSucceeds() {
        when(eventService.getEventDetail(TOKEN, EVENT_ID)).thenReturn(eventInCompany(COMPANY_ID));
        VenueMapConfigDTO config = config();

        VenueMapPresenter.SaveOutcome outcome =
                presenter.saveMap(TOKEN, String.valueOf(EVENT_ID), config);

        assertInstanceOf(VenueMapPresenter.SaveOutcome.Success.class, outcome);
        // The companyId handed to configureVenueMap is the EVENT's company, not any owned-company list.
        verify(eventService).configureVenueMap(eq(TOKEN), eq(COMPANY_ID), eq(config));
    }

    @Test
    void saveMap_invalidToken_returnsNotAuthenticated() {
        when(eventService.getEventDetail(eq(TOKEN), anyInt()))
                .thenThrow(new InvalidTokenException("bad"));

        assertInstanceOf(VenueMapPresenter.SaveOutcome.NotAuthenticated.class,
                presenter.saveMap(TOKEN, String.valueOf(EVENT_ID), config()));
    }

    @Test
    void saveMap_serviceThrowsRuntime_returnsFailureWithMessage() {
        when(eventService.getEventDetail(TOKEN, EVENT_ID)).thenReturn(eventInCompany(COMPANY_ID));
        doThrow(new IllegalStateException("Cannot reconfigure venue map while tickets are reserved or sold"))
                .when(eventService).configureVenueMap(eq(TOKEN), eq(COMPANY_ID), any());

        VenueMapPresenter.SaveOutcome.Failure fail =
                assertInstanceOf(VenueMapPresenter.SaveOutcome.Failure.class,
                        presenter.saveMap(TOKEN, String.valueOf(EVENT_ID), config()));

        assertEquals("Cannot reconfigure venue map while tickets are reserved or sold", fail.reason());
    }

    // ---- helpers --------------------------------------------------------

    private static VenueMapConfigDTO config() {
        return new VenueMapConfigDTO(String.valueOf(EVENT_ID), "Venue", 1, 1, List.of());
    }

    private static EventDetailDTO eventInCompany(int companyId) {
        return new EventDetailDTO(String.valueOf(EVENT_ID), "Show", null, null, null, null,
                String.valueOf(companyId), "Company " + companyId, EventStatus.SCHEDULED,
                List.of(), List.of(), 0.0);
    }
}
