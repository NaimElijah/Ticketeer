package com.ticketing.system.ui.presenters.catalog;

import org.springframework.stereotype.Component;

import com.ticketing.system.shared.dto.CatalogSearchFiltersDTO;
import com.ticketing.system.shared.dto.EventSummaryDTO;
import com.ticketing.system.catalog.application.service.CatalogService;
import com.ticketing.system.ui.presenters.ListOutcome;
import com.ticketing.system.ui.support.ServiceErrors;

import lombok.extern.slf4j.Slf4j;

/**
 * Read-only presenter for {@code BrowseEventsView} (#271). Runs the public catalog search
 * server-side via {@link CatalogService#searchGlobal}: the view builds a
 * {@link CatalogSearchFiltersDTO} from its filter chips/selects and re-queries on every change
 * (an empty filter is the unfiltered catalog).
 *
 * <p>Holds no Vaadin imports. Each read returns a {@link ListOutcome}, so a failed read reaches the
 * view as a failure with {@code ServiceErrors}' text, never as an empty catalog.
 */
@Slf4j
@Component
public class BrowseEventsPresenter {

    private final CatalogService catalogService;

    public BrowseEventsPresenter(CatalogService catalogService) {
        this.catalogService = catalogService;
    }

    /** Server-side filtered catalog (empty filter = full on-sale catalog), or the read's failure. */
    public ListOutcome<EventSummaryDTO> search(String credential, CatalogSearchFiltersDTO filters) {
        try {
            return new ListOutcome.Loaded<>(catalogService.searchGlobal(credential, filters));
        } catch (RuntimeException e) {
            log.warn("Browse search failed: {}", e.getMessage());
            return new ListOutcome.Failure<>(ServiceErrors.userMessage(e));
        }
    }

    /** Distinct countries that currently have on-sale events, or the read's failure. */
    public ListOutcome<String> countries() {
        try {
            return new ListOutcome.Loaded<>(catalogService.onSaleCountries());
        } catch (RuntimeException e) {
            log.warn("Browse countries failed: {}", e.getMessage());
            return new ListOutcome.Failure<>(ServiceErrors.userMessage(e));
        }
    }

    /** Distinct cities of on-sale events in {@code country}, or the read's failure. */
    public ListOutcome<String> cities(String country) {
        try {
            return new ListOutcome.Loaded<>(catalogService.onSaleCitiesInCountry(country));
        } catch (RuntimeException e) {
            log.warn("Browse cities failed: {}", e.getMessage());
            return new ListOutcome.Failure<>(ServiceErrors.userMessage(e));
        }
    }
}
