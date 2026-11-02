package com.ticketing.system.unit.presentation;

import static com.ticketing.system.unit.presentation.ViewHarness.find;
import static com.ticketing.system.unit.presentation.ViewHarness.routeTo;
import static com.ticketing.system.unit.presentation.ViewHarness.text;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.catalog.application.dto.EventDetailDTO;
import com.ticketing.system.catalog.application.service.EventManagementService;
import com.ticketing.system.catalog.domain.EventCategory;
import com.ticketing.system.catalog.domain.EventStatus;
import com.ticketing.system.organization.application.service.CompanyManagementService;
import com.ticketing.system.reporting.application.dto.SalesSummaryDTO;
import com.ticketing.system.shared.dto.EventSummaryDTO;
import com.ticketing.system.shared.dto.MyCompanyDTO;
import com.ticketing.system.ui.components.kit.LkSelect;
import com.ticketing.system.ui.presenters.ListOutcome;
import com.ticketing.system.ui.presenters.admin.GlobalHistoryPresenter;
import com.ticketing.system.ui.presenters.catalog.BrowseEventsPresenter;
import com.ticketing.system.ui.presenters.company.CompanyEventListPresenter;
import com.ticketing.system.ui.presenters.company.PurchasePolicyEditorPresenter;
import com.ticketing.system.ui.session.SessionIdentity;
import com.ticketing.system.ui.views.admin.GlobalHistoryView;
import com.ticketing.system.ui.views.catalog.BrowseEventsView;
import com.ticketing.system.ui.views.catalog.CatalogFilterSupport;
import com.ticketing.system.ui.views.company.CompanyEventListView;
import com.ticketing.system.ui.views.company.PurchasePolicyEditorView;
import com.vaadin.flow.component.Component;

/**
 * A list that fails to load shows why, and never an empty state that would read as "there is nothing"
 * (#187): the views render the presenters' {@link ListOutcome.Failure}.
 */
class FailedListViewsTest {

    private static final String REASON = "reason-under-test";
    private static final String COUNTRY = "Israel";
    private static final String NO_EVENTS = "No events match your filters";

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
    void GivenTheSearchFails_WhenTheBrowsePageShows_ThenItShowsWhyAndNoEmptyState() {
        BrowseEventsPresenter presenter = browsePresenter();
        when(presenter.search(any(), any())).thenReturn(new ListOutcome.Failure<>(REASON));

        assertShowsWhyAndNot(text(browsePage(presenter)), NO_EVENTS);
    }

    @Test
    void GivenTheCitiesFailToLoad_WhenTheBuyerChoosesACountry_ThenItShowsWhyAndNoEmptyState() {
        BrowseEventsPresenter presenter = browsePresenter();
        when(presenter.cities(COUNTRY)).thenReturn(new ListOutcome.Failure<>(REASON));
        BrowseEventsView view = browsePage(presenter);

        countrySelect(view).setValue(COUNTRY);

        assertShowsWhyAndNot(text(view), NO_EVENTS);
    }

    @Test
    void GivenTheHistoryFailsToLoad_WhenTheAdminOpensIt_ThenItShowsWhyAndNoEmptyState() {
        GlobalHistoryPresenter presenter = mock(GlobalHistoryPresenter.class);
        when(presenter.loadAllRecords()).thenReturn(new ListOutcome.Failure<>(REASON));
        // A view that rendered the failure as an empty list would summarise it, so the mock answers that too.
        when(presenter.summarize(any())).thenReturn(new SalesSummaryDTO(0, 0, 0, Optional.empty(), 0));

        assertShowsWhyAndNot(text(new GlobalHistoryView(presenter, InboxViewHarness.displayTime())), "No orders match");
    }

    @Test
    void GivenTheCitiesFailToLoad_WhenTheOwnerChoosesACountry_ThenItShowsWhyAndNoEmptyState() {
        CompanyEventListPresenter presenter = mock(CompanyEventListPresenter.class);
        when(presenter.load(any(), any(), any())).thenReturn(new CompanyEventListPresenter.Outcome.Success(List.of(
            new EventDetailDTO("9", "Hamlet", 4.5, "Live", EventCategory.THEATER, null, "3", "Acme", EventStatus.ON_SALE,
                List.of(), ZoneOffset.UTC, List.of(), 50.0))));
        when(presenter.countries(any(), any())).thenReturn(new ListOutcome.Loaded<>(List.of(COUNTRY)));
        when(presenter.cities(any(), any(), eq(COUNTRY))).thenReturn(new ListOutcome.Failure<>(REASON));
        CompanyEventListView view = new CompanyEventListView(presenter, InboxViewHarness.displayTime());

        countrySelect(view).setValue(COUNTRY);

        assertShowsWhyAndNot(text(view), NO_EVENTS);
    }

    @Test
    void GivenTheEventsFailToLoad_WhenThePolicyEditorOpens_ThenItShowsWhy() {
        // The real presenter builds and validates the tree; only its service-backed reads are stubbed.
        PurchasePolicyEditorPresenter presenter = spy(new PurchasePolicyEditorPresenter(
            mock(CompanyManagementService.class), mock(EventManagementService.class), mock(SessionIdentity.class)));
        doReturn(new PurchasePolicyEditorPresenter.Identity(ViewHarness.MEMBER_TOKEN)).when(presenter).resolveIdentity();
        doReturn(new PurchasePolicyEditorPresenter.ContextOutcome.Ready(List.of(new MyCompanyDTO(3, "Acme", "Founder"))))
            .when(presenter).resolveContext(any());
        doReturn(new ListOutcome.Failure<>(REASON)).when(presenter).listEvents(any(), anyInt());
        doReturn(new PurchasePolicyEditorPresenter.LoadOutcome.Success(presenter.emptyPolicy()))
            .when(presenter).load(any(), anyInt(), anyInt(), anyBoolean());
        PurchasePolicyEditorView view = new PurchasePolicyEditorView(presenter);

        view.beforeEnter(routeTo());

        // The editor has no empty-state text: an empty event list only leaves the select on its placeholder.
        assertTrue(text(view).contains(REASON), "the failure's reason must show: " + text(view));
    }

    private static BrowseEventsPresenter browsePresenter() {
        BrowseEventsPresenter presenter = mock(BrowseEventsPresenter.class);
        when(presenter.search(any(), any())).thenReturn(new ListOutcome.Loaded<>(List.of(new EventSummaryDTO(1,
            "Hamlet", "ON_SALE", null, "Acme", EventCategory.THEATER.name(), "Tel Aviv", List.of(), ZoneOffset.UTC,
            50.0, 50.0, false, List.of()))));
        when(presenter.countries()).thenReturn(new ListOutcome.Loaded<>(List.of(COUNTRY)));
        return presenter;
    }

    private static BrowseEventsView browsePage(BrowseEventsPresenter presenter) {
        return new BrowseEventsView(presenter, mock(SessionIdentity.class), InboxViewHarness.displayTime());
    }

    private static LkSelect countrySelect(Component view) {
        return find(view, LkSelect.class).stream()
            .filter(select -> CatalogFilterSupport.COUNTRY_ALL.equals(select.getValue()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("the page has no country filter"));
    }

    private static void assertShowsWhyAndNot(String shown, String emptyState) {
        assertTrue(shown.contains(REASON), "the failure's reason must show: " + shown);
        assertFalse(shown.contains(emptyState), "a failed load must not read as an empty one: " + shown);
    }
}
