package com.ticketing.system.unit.presentation;

import static com.ticketing.system.unit.presentation.ViewHarness.find;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.shared.dto.EventSummaryDTO;
import com.ticketing.system.ui.components.kit.LkBanner;
import com.ticketing.system.ui.components.kit.LkSelect;
import com.ticketing.system.ui.presenters.ListOutcome;
import com.ticketing.system.ui.presenters.catalog.BrowseEventsPresenter;
import com.ticketing.system.ui.presenters.company.CompanyEventListPresenter;
import com.ticketing.system.ui.session.SessionIdentity;
import com.ticketing.system.ui.views.catalog.BrowseEventsView;
import com.ticketing.system.ui.views.catalog.CatalogFilterSupport;
import com.ticketing.system.ui.views.company.CompanyEventListView;
import com.vaadin.flow.component.Component;

/**
 * An error banner that a search or a filter choice inserts into the page is announced to a screen
 * reader as it appears: it carries {@code role="alert"} (#187).
 */
class ErrorBannerAlertTest {

    private static final String REASON = "reason-under-test";
    private static final String COUNTRY = "Israel";

    private ViewHarness harness;

    @BeforeEach
    void setUp() {
        harness = new ViewHarness();
        ViewHarness.signInMember();
    }

    @AfterEach
    void tearDown() {
        harness.close();
    }

    @Test
    void GivenTheSearchFails_WhenTheBrowsePageShowsIt_ThenTheErrorBannerIsAnAlert() {
        BrowseEventsPresenter presenter = browsePresenter();
        when(presenter.search(any(), any())).thenReturn(new ListOutcome.Failure<>(REASON));

        BrowseEventsView view = new BrowseEventsView(presenter, mock(SessionIdentity.class),
            InboxViewHarness.displayTime());

        assertEquals("alert", roleOfTheBannerSaying(view, REASON));
    }

    @Test
    void GivenTheCitiesFailToLoad_WhenTheBuyerChoosesACountry_ThenTheErrorBannerIsAnAlert() {
        BrowseEventsPresenter presenter = browsePresenter();
        when(presenter.cities(COUNTRY)).thenReturn(new ListOutcome.Failure<>(REASON));
        BrowseEventsView view = new BrowseEventsView(presenter, mock(SessionIdentity.class),
            InboxViewHarness.displayTime());

        countrySelect(view).setValue(COUNTRY);

        assertEquals("alert", roleOfTheBannerSaying(view, REASON));
    }

    @Test
    void GivenTheCitiesFailToLoad_WhenTheOwnerChoosesACountry_ThenTheErrorBannerIsAnAlert() {
        CompanyEventListPresenter presenter = mock(CompanyEventListPresenter.class);
        when(presenter.load(any(), any(), any())).thenReturn(new CompanyEventListPresenter.Outcome.Success(List.of()));
        when(presenter.countries(any(), any())).thenReturn(new ListOutcome.Loaded<>(List.of(COUNTRY)));
        when(presenter.cities(any(), any(), eq(COUNTRY))).thenReturn(new ListOutcome.Failure<>(REASON));
        CompanyEventListView view = new CompanyEventListView(presenter, InboxViewHarness.displayTime());

        countrySelect(view).setValue(COUNTRY);

        assertEquals("alert", roleOfTheBannerSaying(view, REASON));
    }

    private static BrowseEventsPresenter browsePresenter() {
        BrowseEventsPresenter presenter = mock(BrowseEventsPresenter.class);
        when(presenter.search(any(), any())).thenReturn(new ListOutcome.Loaded<EventSummaryDTO>(List.of()));
        when(presenter.countries()).thenReturn(new ListOutcome.Loaded<>(List.of(COUNTRY)));
        return presenter;
    }

    private static LkSelect countrySelect(Component view) {
        return find(view, LkSelect.class).stream()
            .filter(select -> CatalogFilterSupport.COUNTRY_ALL.equals(select.getValue()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("the page has no country filter"));
    }

    private static String roleOfTheBannerSaying(Component view, String reason) {
        return find(view, LkBanner.class).stream()
            .filter(banner -> banner.getElement().getTextRecursively().contains(reason))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no banner says \"" + reason + "\""))
            .getElement().getAttribute("role");
    }
}
