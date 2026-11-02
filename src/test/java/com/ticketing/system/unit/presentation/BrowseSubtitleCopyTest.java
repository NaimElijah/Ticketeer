package com.ticketing.system.unit.presentation;

import static com.ticketing.system.unit.presentation.ViewHarness.find;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.ui.presenters.ListOutcome;
import com.ticketing.system.ui.presenters.catalog.BrowseEventsPresenter;
import com.ticketing.system.ui.session.SessionIdentity;
import com.ticketing.system.ui.support.EventCategoryLabels;
import com.ticketing.system.ui.views.catalog.BrowseEventsView;
import com.vaadin.flow.component.html.Div;

/**
 * The Browse page's subtitle names only categories that exist, as the landing hero does (#187, UI-A19c).
 */
class BrowseSubtitleCopyTest {

    // ---- UI-A19c: the browse subtitle names only real categories (#187) ----

    private static final String SUBTITLE_CLASS = "bz-hero-sub";
    // The subtitle lists its categories before the dash: "A, b, c — rest of the line".
    private static final String LIST_END = " — ";

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
    void GivenTheBrowsePage_WhenItsSubtitleShows_ThenEveryCategoryItNamesExists() {
        BrowseEventsPresenter presenter = mock(BrowseEventsPresenter.class);
        when(presenter.search(any(), any())).thenReturn(new ListOutcome.Loaded<>(List.of()));
        when(presenter.countries()).thenReturn(new ListOutcome.Loaded<>(List.of()));
        BrowseEventsView view = new BrowseEventsView(presenter, mock(SessionIdentity.class),
            InboxViewHarness.displayTime());

        String subtitle = find(view, Div.class).stream()
            .filter(div -> div.hasClassName(SUBTITLE_CLASS))
            .map(Div::getText)
            .findFirst()
            .orElseThrow(() -> new AssertionError("the Browse page has no subtitle"));

        assertTrue(subtitle.contains(LIST_END), "the subtitle lists no categories: " + subtitle);
        Set<String> labels = EventCategoryLabels.labels().stream()
            .map(label -> label.toLowerCase(Locale.ROOT))
            .collect(Collectors.toSet());
        List<String> unknown = Arrays.stream(subtitle.substring(0, subtitle.indexOf(LIST_END)).split(","))
            .map(name -> name.trim().toLowerCase(Locale.ROOT))
            .filter(name -> !labels.contains(name))
            .toList();
        assertEquals(List.of(), unknown, "the subtitle names categories that do not exist: " + subtitle);
    }
}
