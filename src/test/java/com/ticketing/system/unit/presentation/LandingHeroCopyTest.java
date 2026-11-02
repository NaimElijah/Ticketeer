package com.ticketing.system.unit.presentation;

import static com.ticketing.system.unit.presentation.ViewHarness.find;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

import com.ticketing.system.ui.presenters.landing.LandingPresenter;
import com.ticketing.system.ui.support.EventCategoryLabels;
import com.ticketing.system.ui.views.landing.LandingView;
import com.vaadin.flow.component.html.Div;

/**
 * The landing page's hero names only categories that exist, as its category cards do (#187, UI-A19b).
 */
class LandingHeroCopyTest {

    // ---- UI-A19b: the landing hero names a category that doesn't exist (#187) ----

    private static final String HERO_SUBTITLE_CLASS = "bz-hero-sub";
    // The hero lists its categories before the dash: "A, b, c — rest of the line".
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
    void GivenTheLanding_WhenItsHeroShows_ThenEveryCategoryItNamesExists() {
        LandingPresenter presenter = mock(LandingPresenter.class);
        when(presenter.load()).thenReturn(new LandingPresenter.Outcome.Success(List.of(), List.of()));

        String subtitle = find(new LandingView(presenter), Div.class).stream()
            .filter(div -> div.hasClassName(HERO_SUBTITLE_CLASS))
            .map(Div::getText)
            .findFirst()
            .orElseThrow(() -> new AssertionError("the landing has no hero subtitle"));

        assertTrue(subtitle.contains(LIST_END), "the hero subtitle lists no categories: " + subtitle);
        Set<String> labels = EventCategoryLabels.labels().stream()
            .map(label -> label.toLowerCase(Locale.ROOT))
            .collect(Collectors.toSet());
        List<String> unknown = Arrays.stream(subtitle.substring(0, subtitle.indexOf(LIST_END)).split(","))
            .map(name -> name.trim().toLowerCase(Locale.ROOT))
            .filter(name -> !labels.contains(name))
            .toList();
        assertEquals(List.of(), unknown, "the hero names categories that do not exist: " + subtitle);
    }
}
