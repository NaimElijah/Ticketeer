package com.ticketing.system.unit.presentation;

import static com.ticketing.system.unit.presentation.ViewHarness.domClick;
import static com.ticketing.system.unit.presentation.ViewHarness.find;
import static com.ticketing.system.unit.presentation.ViewHarness.gridColumn;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.catalog.domain.EventCategory;
import com.ticketing.system.shared.dto.EventSummaryDTO;
import com.ticketing.system.ui.presenters.ListOutcome;
import com.ticketing.system.ui.presenters.catalog.BrowseEventsPresenter;
import com.ticketing.system.ui.presenters.landing.LandingPresenter;
import com.ticketing.system.ui.session.SessionIdentity;
import com.ticketing.system.ui.views.catalog.BrowseEventsView;
import com.ticketing.system.ui.views.catalog.CatalogFilterSupport;
import com.ticketing.system.ui.views.landing.LandingView;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.H2;

/**
 * Every event category has one label, the label a browsed event shows is the one that filters back to
 * its category, and the landing page offers only categories that exist (#187, UI-A6).
 */
class CategoryLabelsTest {

    // ---- UI-A6: category labels and filters disagree (#187) ----

    private static final String CATEGORY_QUERY = "category=";

    private ViewHarness harness;

    @BeforeEach
    void setUp() {
        harness = new ViewHarness();
    }

    @AfterEach
    void tearDown() {
        harness.close();
    }

    @Test
    void GivenEveryEventCategory_WhenLookingForTheFilterThatSelectsIt_ThenExactlyOneLabelFiltersToIt() {
        List<String> unfiltered = new ArrayList<>();
        for (EventCategory category : EventCategory.values()) {
            long labels = CatalogFilterSupport.CATEGORIES.stream()
                .filter(label -> category.name().equals(CatalogFilterSupport.categoryFilterValue(label)))
                .count();
            if (labels != 1) {
                unfiltered.add(category + " (" + labels + " labels)");
            }
        }
        assertEquals(List.of(), unfiltered, "each category needs exactly one filter label");
    }

    @Test
    void GivenTheMusicLabel_WhenFiltering_ThenItSelectsMusic() {
        assertTrue(CatalogFilterSupport.CATEGORIES.contains("Music"), "the filter offers no \"Music\"");
        assertEquals("MUSIC", CatalogFilterSupport.categoryFilterValue("Music"));
    }

    @Test
    void GivenAMusicEvent_WhenBrowsed_ThenItsCategoryReadsMusic() {
        BrowseEventsView view = browseWith(event(1, EventCategory.MUSIC));

        assertEquals(List.of("Music"), gridColumn(view, "Category"));
    }

    @Test
    void GivenOneEventOfEachCategory_WhenBrowsed_ThenEachRowsLabelFiltersBackToItsCategory() {
        EventCategory[] categories = EventCategory.values();
        EventSummaryDTO[] events = new EventSummaryDTO[categories.length];
        for (int i = 0; i < categories.length; i++) {
            events[i] = event(i + 1, categories[i]);
        }
        BrowseEventsView view = browseWith(events);

        // The browse page sorts its rows, so compare the categories the labels filter to, in name order.
        List<String> labels = gridColumn(view, "Category");
        List<String> filtered = labels.stream()
            .map(label -> String.valueOf(CatalogFilterSupport.categoryFilterValue(label)))
            .sorted()
            .toList();
        assertEquals(Arrays.stream(categories).map(Enum::name).sorted().toList(), filtered,
            "browse labels " + labels + " must each filter back to their own category");
    }

    @Test
    void GivenTheLanding_WhenEachCategoryCardIsClicked_ThenEachOpensBrowseOnACategoryThatExists() {
        LandingPresenter presenter = mock(LandingPresenter.class);
        when(presenter.load()).thenReturn(new LandingPresenter.Outcome.Success(List.of(), List.of()));
        LandingView view = new LandingView(presenter);

        List<Component> cards = categoryCards(view);
        cards.forEach(card -> domClick(card.getElement()));

        assertFalse(cards.isEmpty(), "the landing shows no category cards");
        List<String> chosen = harness.navigations().stream().map(CategoryLabelsTest::categoryOf).toList();
        List<String> unknown = chosen.stream()
            .filter(label -> CatalogFilterSupport.categoryFilterValue(label) == null)
            .toList();
        assertEquals(List.of(), unknown, "landing cards for categories browse cannot filter, of " + chosen);
    }

    // ---- arrangement ----

    private static EventSummaryDTO event(int id, EventCategory category) {
        return new EventSummaryDTO(id, "Event " + id, "ON_SALE", null, "Acme", category.name(), "Tel Aviv",
            List.of(), ZoneOffset.UTC, 50.0, 50.0, false, List.of());
    }

    private static BrowseEventsView browseWith(EventSummaryDTO... events) {
        BrowseEventsPresenter presenter = mock(BrowseEventsPresenter.class);
        when(presenter.search(any(), any())).thenReturn(new ListOutcome.Loaded<>(List.of(events)));
        when(presenter.countries()).thenReturn(new ListOutcome.Loaded<>(List.of()));
        return new BrowseEventsView(presenter, mock(SessionIdentity.class), InboxViewHarness.displayTime());
    }

    // The cards are the children of the block that follows the "Browse by Category" heading.
    private static List<Component> categoryCards(LandingView view) {
        H2 heading = find(view, H2.class).stream()
            .filter(h -> "Browse by Category".equals(h.getText()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("the landing has no \"Browse by Category\" heading"));
        List<Component> siblings = heading.getParent().orElseThrow().getChildren().toList();
        Component grid = siblings.get(siblings.indexOf(heading) + 1);
        return grid.getChildren().toList();
    }

    private static String categoryOf(String navigation) {
        int at = navigation.indexOf(CATEGORY_QUERY);
        if (at < 0) {
            return navigation;
        }
        String value = navigation.substring(at + CATEGORY_QUERY.length()).split("&")[0];
        return Objects.requireNonNull(URLDecoder.decode(value, StandardCharsets.UTF_8));
    }
}
