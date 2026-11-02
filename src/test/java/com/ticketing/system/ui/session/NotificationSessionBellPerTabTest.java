package com.ticketing.system.ui.session;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.time.LocalDateTime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.shared.dto.AuthTokenDTO;
import com.ticketing.system.ui.components.NotificationBellComponent;
import com.ticketing.system.ui.presenters.notifications.NotificationBellPresenter.MarkReadOutcome;
import com.vaadin.flow.component.UI;

/**
 * Each browser tab of one Vaadin session is its own {@link UI} with its own bell, and the notification
 * poller refreshes the bell of the UI it is running in ({@code ui.access}, which makes that UI the
 * current one). So {@link NotificationSession#getBell()} answers the current UI's bell: a second tab
 * must not replace the first tab's bell, and closing one tab must not drop the other's.
 *
 * <p>The bells attach to and detach from real, headless UIs, so their own attach and detach listeners
 * register and unregister them.
 */
class NotificationSessionBellPerTabTest {

    private final UI firstTab = new UI();
    private final UI secondTab = new UI();

    @BeforeEach
    void setUp() {
        MapBackedVaadinSession.install();
        AuthSession.storeAuth(new AuthTokenDTO("member-jwt", 0L, 42, "member"));
    }

    @AfterEach
    void tearDown() {
        UI.setCurrent(null);
        MapBackedVaadinSession.uninstall();
    }

    // ---- UI-B8: one bell per tab, not per session (#187) ----

    @Test
    void GivenTwoTabsEachWithABell_WhenEachTabAsksForItsBell_ThenEachGetsItsOwn() {
        NotificationBellComponent firstBell = attachBellTo(firstTab);
        NotificationBellComponent secondBell = attachBellTo(secondTab);

        assertSame(firstBell, bellAsSeenFrom(firstTab), "the second tab's bell must not replace the first tab's");
        assertSame(secondBell, bellAsSeenFrom(secondTab));
    }

    @Test
    void GivenTwoTabsEachWithABell_WhenOneTabsBellDetaches_ThenTheOtherTabStillHasItsBell() {
        NotificationBellComponent firstBell = attachBellTo(firstTab);
        NotificationBellComponent secondBell = attachBellTo(secondTab);

        UI.setCurrent(secondTab);
        secondTab.remove(secondBell);

        assertSame(firstBell, bellAsSeenFrom(firstTab), "one tab's detach must not drop the other tab's bell");
        assertNull(bellAsSeenFrom(secondTab), "the detached bell must no longer be refreshed");
    }

    // A navigation to another layout can attach the new layout's bell before the old one detaches.
    @Test
    void GivenANewBellAttachedToATabBeforeTheOldOneDetaches_WhenTheOldOneDetaches_ThenTheTabKeepsTheNewBell() {
        NotificationBellComponent oldBell = attachBellTo(firstTab);
        NotificationBellComponent newBell = attachBellTo(firstTab);

        firstTab.remove(oldBell);

        assertSame(newBell, bellAsSeenFrom(firstTab), "the old bell's detach must remove only itself");
    }

    private static NotificationBellComponent attachBellTo(UI tab) {
        UI.setCurrent(tab);
        NotificationBellComponent bell = new NotificationBellComponent(
                id -> new MarkReadOutcome.Success(), (LocalDateTime createdAt) -> "now");
        tab.add(bell);
        return bell;
    }

    private static NotificationBellComponent bellAsSeenFrom(UI tab) {
        UI.setCurrent(tab);
        return NotificationSession.getBell();
    }
}
