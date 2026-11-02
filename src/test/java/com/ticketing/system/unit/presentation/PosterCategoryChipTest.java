package com.ticketing.system.unit.presentation;

import static com.ticketing.system.unit.presentation.ViewHarness.find;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.catalog.domain.EventCategory;
import com.ticketing.system.shared.dto.EventSummaryDTO;
import com.ticketing.system.ui.components.buyer.BzPoster;
import com.ticketing.system.ui.presenters.landing.LandingPresenter;
import com.ticketing.system.ui.support.EventCategoryLabels;
import com.ticketing.system.ui.views.landing.LandingView;
import com.vaadin.flow.component.HtmlContainer;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;

/**
 * A featured poster on the landing page names its event's category with the label the rest of the UI
 * uses for it, not the poster's colour family (#187, UI-A6b): a music or festival event is not a
 * "concert", and a comedy is not "theatre".
 */
class PosterCategoryChipTest {

    // ---- UI-A6b: a poster's chip reads its category's label (#187) ----

    private static final String CHIP_CLASS = "bz-poster-cat";
    private static final String TITLE_CLASS = "bz-poster-title";
    private static final List<EventCategory> CATEGORIES =
        List.of(EventCategory.MUSIC, EventCategory.FESTIVAL, EventCategory.COMEDY);

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
    void GivenFeaturedMusicFestivalAndComedyEvents_WhenTheLandingShowsThem_ThenEachPosterChipReadsItsCategorysLabel() {
        LandingPresenter presenter = mock(LandingPresenter.class);
        List<EventSummaryDTO> featured = CATEGORIES.stream().map(PosterCategoryChipTest::eventOf).toList();
        when(presenter.load()).thenReturn(new LandingPresenter.Outcome.Success(featured, List.of()));

        LandingView view = new LandingView(presenter);

        Map<String, String> expected = new LinkedHashMap<>();
        CATEGORIES.forEach(category -> expected.put(titleOf(category), chipText(EventCategoryLabels.label(category))));
        Map<String, String> shown = new LinkedHashMap<>();
        for (BzPoster poster : find(view, BzPoster.class)) {
            shown.put(textOfClass(poster, Div.class, TITLE_CLASS), textOfClass(poster, Span.class, CHIP_CLASS));
        }
        assertEquals(expected, shown, "each poster's chip must read its category's label");
    }

    private static EventSummaryDTO eventOf(EventCategory category) {
        return new EventSummaryDTO(category.ordinal() + 1, titleOf(category), "ON_SALE", null, "Acme",
            category.name(), "Tel Aviv", List.of(), ZoneOffset.UTC, 50.0, 50.0, false, List.of());
    }

    private static String titleOf(EventCategory category) {
        return "A " + category.name().toLowerCase(Locale.ROOT) + " night";
    }

    // The chip shows its label in capitals, as it always has.
    private static String chipText(String label) {
        return label.toUpperCase(Locale.ROOT);
    }

    private static <T extends HtmlContainer> String textOfClass(BzPoster poster, Class<T> type, String className) {
        return find(poster, type).stream()
            .filter(component -> component.hasClassName(className))
            .map(HtmlContainer::getText)
            .findFirst()
            .orElseThrow(() -> new AssertionError("a poster without a " + className));
    }
}
