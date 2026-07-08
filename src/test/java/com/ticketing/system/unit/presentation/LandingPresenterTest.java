package com.ticketing.system.unit.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.Core.Application.dto.EventSummaryDTO;
import com.ticketing.system.catalog.application.service.CatalogService;
import com.ticketing.system.Presentation.presenters.landing.LandingPresenter;

class LandingPresenterTest {

    private CatalogService catalogService;
    private LandingPresenter presenter;

    @BeforeEach
    void setUp() {
        catalogService = mock(CatalogService.class);
        presenter = new LandingPresenter(catalogService);
    }

    private static EventSummaryDTO event(int id, String name) {
        return new EventSummaryDTO(id, name, "ON_SALE", 4.5, "Acme", "MUSIC",
            "Tel Aviv", List.of(), 80.0, 250.0, false, List.of(name));
    }

    @Test
    void load_success_carriesBothRowsFromService() {
        EventSummaryDTO featured = event(1, "Coldplay");
        EventSummaryDTO upcoming = event(2, "Mashina");
        when(catalogService.featured(4)).thenReturn(List.of(featured));
        when(catalogService.upcomingOnSale(4)).thenReturn(List.of(upcoming));

        LandingPresenter.Outcome outcome = presenter.load();

        LandingPresenter.Outcome.Success ok =
            assertInstanceOf(LandingPresenter.Outcome.Success.class, outcome);
        assertEquals(List.of(featured), ok.featured());
        assertEquals(List.of(upcoming), ok.upcoming());
        // Both rows are sourced with the page's 4-up limit.
        verify(catalogService).featured(4);
        verify(catalogService).upcomingOnSale(4);
    }

    @Test
    void load_serviceThrows_returnsFailureWithMessage() {
        when(catalogService.featured(4)).thenThrow(new RuntimeException("catalog down"));

        LandingPresenter.Outcome outcome = presenter.load();

        LandingPresenter.Outcome.Failure fail =
            assertInstanceOf(LandingPresenter.Outcome.Failure.class, outcome);
        assertEquals("catalog down", fail.reason());
    }
}
