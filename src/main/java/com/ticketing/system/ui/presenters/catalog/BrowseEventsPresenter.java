package com.ticketing.system.ui.presenters.catalog;

import java.util.List;

import org.springframework.stereotype.Component;

import com.ticketing.system.shared.dto.CatalogSearchFiltersDTO;
import com.ticketing.system.shared.dto.EventSummaryDTO;
import com.ticketing.system.catalog.application.service.CatalogService;

import lombok.extern.slf4j.Slf4j;

/**
 * Read-only presenter for {@code BrowseEventsView} (#271). Runs the public catalog search
 * server-side via {@link CatalogService#searchGlobal}: the view builds a
 * {@link CatalogSearchFiltersDTO} from its filter chips/selects and re-queries on every change
 * (an empty filter is the unfiltered catalog).
 *
 * <p>Holds no Vaadin imports. A read-only query, so it returns the DTO list directly (no
 * {@code Outcome}) and degrades to an empty list on failure so the view shows its empty state
 * rather than crashing.
 */
@Slf4j
@Component
public class BrowseEventsPresenter {

    private final CatalogService catalogService;

    public BrowseEventsPresenter(CatalogService catalogService) {
        this.catalogService = catalogService;
    }

    /** Server-side filtered catalog (empty filter = full on-sale catalog); empty list on failure. */
    public List<EventSummaryDTO> search(String credential, CatalogSearchFiltersDTO filters) {
        try {
            return catalogService.searchGlobal(credential, filters);
        } catch (RuntimeException e) {
            log.warn("Browse search failed: {}", e.getMessage());
            return List.of();
        }
    }

    /** Distinct countries that currently have on-sale events; empty list on failure. */
    public List<String> countries() {
        try {
            return catalogService.onSaleCountries();
        } catch (RuntimeException e) {
            log.warn("Browse countries failed: {}", e.getMessage());
            return List.of();
        }
    }

    /** Distinct cities of on-sale events in {@code country}; empty list on failure. */
    public List<String> cities(String country) {
        try {
            return catalogService.onSaleCitiesInCountry(country);
        } catch (RuntimeException e) {
            log.warn("Browse cities failed: {}", e.getMessage());
            return List.of();
        }
    }
}
