package com.ticketing.system.ui.presenters.landing;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.ticketing.system.shared.dto.EventSummaryDTO;
import com.ticketing.system.catalog.application.service.CatalogService;
import com.ticketing.system.ui.support.ServiceErrors;

/**
 * MVP presenter for {@code LandingView} (V2-LANDING-01). Holds no Vaadin imports so the
 * outcome → UI translation lives in the view and the service-call decision tree is
 * unit-testable in isolation (mirrors {@code MyInvitationsPresenter}).
 *
 * <p>Loads the two data-backed rows of the public landing page in one call: the
 * <em>featured</em> events (best-rated ON_SALE) and the <em>on sale soon</em> teaser
 * (SCHEDULED). The page is anonymous, so there is no token / {@code NotAuthenticated}
 * branch — a backend hiccup degrades to {@link Outcome.Failure}, which the view renders
 * as a graceful empty state rather than an error banner.
 */
@Component
public class LandingPresenter {

    /** Number of posters each landing row shows (matches the 4-up poster grid). */
    private static final int FEATURED_LIMIT = 4;
    private static final int UPCOMING_LIMIT = 4;

    private final CatalogService catalogService;

    /** Creates the presenter over the catalog service. */
    @Autowired
    public LandingPresenter(CatalogService catalogService) {
        this.catalogService = catalogService;
    }

    /** Loads the featured and on-sale-soon rows; any backend error collapses to a Failure. */
    public Outcome load() {
        try {
            List<EventSummaryDTO> featured = catalogService.featured(FEATURED_LIMIT);
            List<EventSummaryDTO> upcoming = catalogService.upcomingOnSale(UPCOMING_LIMIT);
            return new Outcome.Success(featured, upcoming);
        } catch (RuntimeException e) {
            return new Outcome.Failure(ServiceErrors.userMessage(e));
        }
    }

    /** Sealed outcome the view switches on to render the poster rows or an empty state. */
    public sealed interface Outcome {
        /**
         * Both rows loaded.
         *
         * @param featured the best-rated on-sale events, at most the row's poster count
         * @param upcoming the scheduled events with the soonest show date, at most the row's poster count
         */
        record Success(List<EventSummaryDTO> featured, List<EventSummaryDTO> upcoming) implements Outcome { }

        /**
         * Either row failed to load.
         *
         * @param reason the text the user may read, from {@code ServiceErrors.userMessage}
         */
        record Failure(String reason) implements Outcome { }
    }
}
