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

import com.ticketing.system.shared.dto.SearchResultDTO;
import com.ticketing.system.catalog.application.service.CatalogService;
import com.ticketing.system.testutil.LogCapture;
import com.ticketing.system.ui.presenters.catalog.SearchPresenter;
import com.ticketing.system.ui.session.MapBackedVaadinSession;

import ch.qos.logback.classic.Level;

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
    void GivenMatchingRows_WhenSearch_ThenDelegatesToCatalogAndReturnsRows() {
        List<SearchResultDTO> rows = List.of(new SearchResultDTO("EVENT", "Coldplay", "Live Nation", 1));
        when(catalogService.search(eq(CRED), eq("coldplay"), anyInt())).thenReturn(rows);

        List<SearchResultDTO> result = presenter.search(CRED, "coldplay");

        assertSame(rows, result);
    }

    @Test
    void GivenAServiceFailure_WhenSearch_ThenReturnsEmpty() {
        when(catalogService.search(eq(CRED), eq("coldplay"), anyInt()))
                .thenThrow(new RuntimeException("boom"));

        assertTrue(presenter.search(CRED, "coldplay").isEmpty());
    }

    // A buyer can type anything into the search box, an email address included, so the failure log
    // records the query's length and never its text (#185).
    @Test
    void GivenSearchFails_WhenSearch_ThenNoLogEventCarriesTheQueryText() {
        String query = "dana.levi@example.com";
        when(catalogService.search(eq(CRED), eq(query), anyInt())).thenThrow(new RuntimeException("boom"));

        try (LogCapture logs = LogCapture.of(SearchPresenter.class)) {
            presenter.search(CRED, query);

            assertEquals(List.of("Top-bar search failed for a query of 21 characters: boom"), logs.messagesAt(Level.WARN),
                    "the failure is logged once, at WARN, with the query's length");
            logs.assertNeverLogged(query);
        }
    }

    @Test
    void GivenNoVaadinSession_WhenRecents_ThenReturnsEmpty() {
        // No live VaadinSession in a plain unit test — RecentSearches degrades to empty without throwing.
        assertEquals(List.of(), presenter.recents());
    }

    @Test
    void GivenNoVaadinSession_WhenRecord_ThenDoesNotThrowAndRecentsStayEmpty() {
        // Should not throw even though there is no session to store into.
        presenter.record("coldplay");
        assertEquals(List.of(), presenter.recents());
    }

    // ---- recent searches live on the visitor's session (#189) ----

    @Test
    void GivenASession_WhenTwoQueriesAreRecorded_ThenRecentsListsTheLatestFirstAndTrimmed() {
        MapBackedVaadinSession.install();
        try {
            presenter.record("coldplay");
            presenter.record("  adele ");

            assertEquals(List.of("adele", "coldplay"), presenter.recents());
        } finally {
            MapBackedVaadinSession.uninstall();
        }
    }
}
