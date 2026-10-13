package com.ticketing.system.ui.presenters.catalog;

import java.util.List;

import org.springframework.stereotype.Component;

import com.ticketing.system.shared.dto.SearchResultDTO;
import com.ticketing.system.catalog.application.service.CatalogService;
import com.ticketing.system.ui.session.RecentSearches;

import lombok.extern.slf4j.Slf4j;

/**
 * Presenter for the top-bar search (V2-SEARCH-01 / #281). Vaadin-free; backs the live
 * {@code LkSearchPanel}. Follows {@code BrowseEventsPresenter} — a debounced live search renders
 * its own empty / zero-results states, so this returns a plain list and degrades to empty on
 * failure rather than carrying a sealed Outcome.
 */
@Slf4j
@Component
public class SearchPresenter {

    /** Max rows per query — keeps the dropdown tight. */
    private static final int LIMIT = 8;

    private final CatalogService catalogService;

    public SearchPresenter(CatalogService catalogService) {
        this.catalogService = catalogService;
    }

    /** Live results for a query; empty on blank query or any failure. */
    public List<SearchResultDTO> search(String credential, String query) {
        try {
            return catalogService.search(credential, query, LIMIT);
        } catch (RuntimeException e) {
            log.warn("Top-bar search failed for '{}': {}", query, e.getMessage());
            return List.of();
        }
    }

    /** The visitor's recent searches (most-recent-first); empty outside a Vaadin session. */
    public List<String> recents() {
        return RecentSearches.get();
    }

    /** Records a picked/executed query so it surfaces as a recent search next time. */
    public void record(String query) {
        RecentSearches.add(query);
    }
}
