package com.ticketing.system.unit.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.shared.dto.CatalogSearchFiltersDTO;
import com.ticketing.system.shared.dto.EventSummaryDTO;
import com.ticketing.system.catalog.application.service.CatalogService;
import com.ticketing.system.ui.presenters.ListOutcome;
import com.ticketing.system.ui.presenters.catalog.BrowseEventsPresenter;
import com.ticketing.system.ui.support.ServiceErrors;

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
                "CONCERT", "Tel Aviv, Israel", List.of(), ZoneId.of("Asia/Jerusalem"), 90.0, 250.0, false, List.of("Coldplay"));
    }

    @Test
    void search_delegatesToSearchGlobalAndReturnsResults() {
        CatalogSearchFiltersDTO filters = CatalogSearchFiltersDTO.empty();
        List<EventSummaryDTO> events = List.of(summary());
        when(catalogService.searchGlobal(eq(CRED), eq(filters))).thenReturn(events);

        ListOutcome<EventSummaryDTO> result = presenter.search(CRED, filters);

        assertEquals(new ListOutcome.Loaded<>(events), result);
    }

    // A failed search is a failure, never an empty catalog (UI-B3, #187).
    @Test
    void search_onServiceFailure_returnsFailureWithTheGenericMessage() {
        when(catalogService.searchGlobal(eq(CRED), any())).thenThrow(new RuntimeException("boom"));

        ListOutcome.Failure<?> failure = assertInstanceOf(ListOutcome.Failure.class,
                presenter.search(CRED, CatalogSearchFiltersDTO.empty()));
        assertEquals(ServiceErrors.GENERIC_FAILURE_MESSAGE, failure.reason());
    }
}
