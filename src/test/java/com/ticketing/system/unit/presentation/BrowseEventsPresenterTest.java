package com.ticketing.system.unit.presentation;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.Core.Application.dto.CatalogSearchFiltersDTO;
import com.ticketing.system.Core.Application.dto.EventSummaryDTO;
import com.ticketing.system.catalog.application.service.CatalogService;
import com.ticketing.system.Presentation.presenters.catalog.BrowseEventsPresenter;

class BrowseEventsPresenterTest {

    private static final String CRED = "cred";

    private CatalogService catalogService;
    private BrowseEventsPresenter presenter;

    @BeforeEach
    void setUp() {
        catalogService = mock(CatalogService.class);
        presenter = new BrowseEventsPresenter(catalogService);
    }

    private static EventSummaryDTO summary() {
        return new EventSummaryDTO(1, "Coldplay", "ON_SALE", 4.8, "Live Nation",
                "CONCERT", "Tel Aviv, Israel", List.of(), 90.0, 250.0, false, List.of("Coldplay"));
    }

    @Test
    void search_delegatesToSearchGlobalAndReturnsResults() {
        CatalogSearchFiltersDTO filters = CatalogSearchFiltersDTO.empty();
        List<EventSummaryDTO> events = List.of(summary());
        when(catalogService.searchGlobal(eq(CRED), eq(filters))).thenReturn(events);

        List<EventSummaryDTO> result = presenter.search(CRED, filters);

        assertSame(events, result);
    }

    @Test
    void search_onServiceFailure_returnsEmpty() {
        when(catalogService.searchGlobal(eq(CRED), any())).thenThrow(new RuntimeException("boom"));

        assertTrue(presenter.search(CRED, CatalogSearchFiltersDTO.empty()).isEmpty());
    }
}
