package com.ticketing.system.unit.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.Core.Application.dto.SearchResultDTO;
import com.ticketing.system.catalog.application.service.CatalogService;
import com.ticketing.system.Presentation.presenters.catalog.SearchPresenter;

class SearchPresenterTest {

    private static final String CRED = "cred";

    private CatalogService catalogService;
    private SearchPresenter presenter;

    @BeforeEach
    void setUp() {
        catalogService = mock(CatalogService.class);
        presenter = new SearchPresenter(catalogService);
    }

    @Test
    void search_delegatesToCatalogAndReturnsRows() {
        List<SearchResultDTO> rows = List.of(new SearchResultDTO("EVENT", "Coldplay", "Live Nation", 1));
        when(catalogService.search(eq(CRED), eq("coldplay"), anyInt())).thenReturn(rows);

        List<SearchResultDTO> result = presenter.search(CRED, "coldplay");

        assertSame(rows, result);
    }

    @Test
    void search_onServiceFailure_returnsEmpty() {
        when(catalogService.search(eq(CRED), eq("coldplay"), anyInt()))
                .thenThrow(new RuntimeException("boom"));

        assertTrue(presenter.search(CRED, "coldplay").isEmpty());
    }

    @Test
    void recents_withNoVaadinSession_returnsEmpty() {
        // No live VaadinSession in a plain unit test — RecentSearches degrades to empty without throwing.
        assertEquals(List.of(), presenter.recents());
    }

    @Test
    void record_withNoVaadinSession_isNoOp() {
        // Should not throw even though there is no session to store into.
        presenter.record("coldplay");
        assertEquals(List.of(), presenter.recents());
    }
}
