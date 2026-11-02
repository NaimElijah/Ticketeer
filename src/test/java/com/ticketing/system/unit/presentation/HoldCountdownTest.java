package com.ticketing.system.unit.presentation;

import static com.ticketing.system.unit.presentation.ViewHarness.MEMBER_ID;
import static com.ticketing.system.unit.presentation.ViewHarness.MEMBER_TOKEN;
import static com.ticketing.system.unit.presentation.ViewHarness.routeTo;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.shared.dto.ActiveOrderDTO;
import com.ticketing.system.testutil.AdjustableClock;
import com.ticketing.system.ui.layouts.MainLayout;
import com.ticketing.system.ui.presenters.notifications.NotificationBellPresenter;
import com.ticketing.system.ui.presenters.order.CartBadgePresenter;
import com.ticketing.system.ui.presenters.order.CheckoutPresenter;
import com.ticketing.system.ui.security.SignOutFlow;
import com.ticketing.system.ui.views.order.CheckoutView;
import com.vaadin.flow.router.AfterNavigationEvent;

/**
 * A hold's countdown runs in the browser from the time the server says is left, as the cart page's
 * does, so a browser clock that is off does not shorten or stretch it (#187, UI-A13). An epoch deadline
 * computed from the server's clock is compared with the browser's {@code Date.now()}, which is the bug.
 */
class HoldCountdownTest {

    // ---- UI-A13: the countdown compares the server's clock with the browser's (#187) ----

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2027-07-15T17:00:00Z"), ZoneOffset.UTC);
    private static final long REMAINING_SECONDS = 300;
    private static final long ONE_MINUTE_SECONDS = 60;
    // A countdown script ticks with a browser timer; nothing else these pages send uses one.
    private static final List<String> TIMERS = List.of("setTimeout", "setInterval");

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
    void GivenAHoldWithFiveMinutesLeft_WhenTheCheckoutShowsIt_ThenTheBrowserIsSentTheSecondsLeft() {
        CheckoutPresenter presenter = mock(CheckoutPresenter.class);
        when(presenter.resolveIdentity()).thenReturn(new CheckoutPresenter.Identity(true, MEMBER_TOKEN, null, MEMBER_ID));
        when(presenter.loadOrder()).thenReturn(new CheckoutPresenter.LoadOutcome.Loaded(order(),
            new CheckoutPresenter.Pricing(1000, 1000)));
        CheckoutView view = new CheckoutView(presenter, CLOCK);
        harness.attach(view);

        view.beforeEnter(routeTo());
        view.afterNavigation(mock(AfterNavigationEvent.class));

        assertCountdownSentAsSecondsLeft(harness.javaScriptCalls());
    }

    @Test
    void GivenACartWithFiveMinutesLeft_WhenTheTopBarShowsItsTimer_ThenTheBrowserIsSentTheSecondsLeft() {
        CartBadgePresenter badge = mock(CartBadgePresenter.class);
        when(badge.loadBadge()).thenReturn(new CartBadgePresenter.Outcome.Cart(1, REMAINING_SECONDS));
        MainLayout layout = new MainLayout(badge, mock(SignOutFlow.class), mock(NotificationBellPresenter.class), CLOCK);

        harness.attach(layout);

        assertCountdownSentAsSecondsLeft(harness.javaScriptCalls());
    }

    // The checkout can be shown again without reloading the order (beforeEnter on a page already built),
    // so the seconds come from the hold's end on the server's clock, not from the order as first loaded.
    @Test
    void GivenTheCheckoutShownAgainAMinuteLater_WhenItIsEnteredWithoutAReload_ThenTheBrowserIsSentTheSecondsStillLeft() {
        AdjustableClock clock = new AdjustableClock(CLOCK.instant());
        CheckoutPresenter presenter = mock(CheckoutPresenter.class);
        when(presenter.resolveIdentity()).thenReturn(new CheckoutPresenter.Identity(true, MEMBER_TOKEN, null, MEMBER_ID));
        when(presenter.loadOrder()).thenReturn(new CheckoutPresenter.LoadOutcome.Loaded(order(),
            new CheckoutPresenter.Pricing(1000, 1000)));
        CheckoutView view = new CheckoutView(presenter, clock);
        harness.attach(view);
        view.beforeEnter(routeTo());
        view.afterNavigation(mock(AfterNavigationEvent.class));
        harness.javaScriptCalls();

        clock.advance(Duration.ofSeconds(ONE_MINUTE_SECONDS));
        view.beforeEnter(routeTo());

        List<List<Object>> calls = harness.javaScriptCalls();
        List<Object> lastCountdown = calls.stream()
            .filter(call -> TIMERS.stream().anyMatch(String.valueOf(call.get(0))::contains))
            .reduce((first, second) -> second)
            .orElseThrow(() -> new AssertionError("no countdown script was sent; calls: " + calls));
        assertTrue(lastCountdown.stream().anyMatch(p -> p instanceof Number n
                && n.doubleValue() == REMAINING_SECONDS - ONE_MINUTE_SECONDS),
            "a minute later the countdown must get " + (REMAINING_SECONDS - ONE_MINUTE_SECONDS) + " seconds; it got "
                + lastCountdown);
    }

    private static ActiveOrderDTO order() {
        LocalDateTime added = LocalDateTime.ofInstant(CLOCK.instant(), ZoneOffset.UTC);
        return new ActiveOrderDTO(MEMBER_ID, null, added, REMAINING_SECONDS, 10.0,
            List.of(new ActiveOrderDTO.CartLineDTO(5, "Coldplay", 8, "A1", 10.0, added)));
    }

    private static void assertCountdownSentAsSecondsLeft(List<List<Object>> calls) {
        List<Object> countdown = calls.stream()
            .filter(call -> TIMERS.stream().anyMatch(String.valueOf(call.get(0))::contains))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no countdown script was sent; calls: " + calls));
        List<Object> parameters = countdown.subList(1, countdown.size());
        assertTrue(parameters.stream().noneMatch(HoldCountdownTest::isEpochTime),
            "the countdown must not get a deadline on the server's clock; it got " + parameters);
        assertTrue(parameters.stream().anyMatch(p -> p instanceof Number n && n.doubleValue() == REMAINING_SECONDS),
            "the countdown must get the " + REMAINING_SECONDS + " seconds left; it got " + parameters);
    }

    // Any value in the range of epoch milliseconds around the fixed clock.
    private static boolean isEpochTime(Object parameter) {
        return parameter instanceof Number n && n.doubleValue() > CLOCK.millis() / 2.0;
    }
}
