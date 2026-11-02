package com.ticketing.system.unit.infrastructure.scheduling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.bootstrap.scheduling.ActiveUiRegistry;
import com.ticketing.system.bootstrap.scheduling.NotificationPollingScheduler;
import com.ticketing.system.notifications.application.service.NotificationDispatchService;
import com.ticketing.system.shared.dto.AuthTokenDTO;
import com.ticketing.system.shared.dto.NotificationDTO;
import com.ticketing.system.ui.components.NotificationBellComponent;
import com.ticketing.system.ui.presenters.notifications.NotificationBellPresenter.MarkReadOutcome;
import com.ticketing.system.ui.session.AuthSession;
import com.ticketing.system.ui.session.MapBackedVaadinSession;
import com.ticketing.system.ui.session.NotificationSession;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.HasStyle;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.server.Command;

/**
 * The poller delivers a signed-in member's pending notifications to their UI. Notifications are
 * addressed by bare user id, and member and admin ids share one id space, so an admin's UI must not
 * drain the notifications of the member whose id equals the admin's (#185).
 */
class NotificationPollingSchedulerTest {

    private static final int SHARED_ID = 1;
    private static final long TOKEN_EXPIRY_EPOCH_MILLIS = 0L;

    private ActiveUiRegistry uiRegistry;
    private NotificationDispatchService dispatchService;
    private NotificationPollingScheduler scheduler;

    @BeforeEach
    void setUp() {
        MapBackedVaadinSession.install();
        uiRegistry = mock(ActiveUiRegistry.class);
        dispatchService = mock(NotificationDispatchService.class);
        when(dispatchService.deliverPending(anyInt())).thenReturn(List.of());
        scheduler = new NotificationPollingScheduler(uiRegistry, dispatchService);
    }

    @AfterEach
    void tearDown() {
        UI.setCurrent(null);
        MapBackedVaadinSession.uninstall();
    }

    @Test
    void GivenAdminUi_WhenPoll_ThenNoDeliveryForTheAdminsId() {
        AuthSession.storeAuth(new AuthTokenDTO("admin-jwt", TOKEN_EXPIRY_EPOCH_MILLIS, SHARED_ID, "admin", true));
        registerOneUiRunningAccessInline();

        scheduler.poll();

        verify(dispatchService, never()).deliverPending(anyInt());
    }

    @Test
    void GivenMemberUi_WhenPoll_ThenTheMembersPendingNotificationsAreDelivered() {
        AuthSession.storeAuth(new AuthTokenDTO("member-jwt", TOKEN_EXPIRY_EPOCH_MILLIS, SHARED_ID, "member"));
        registerOneUiRunningAccessInline();

        scheduler.poll();

        verify(dispatchService).deliverPending(SHARED_ID);
    }

    // ---- UI-B8b: a tab's bell catches up with another tab's poll (#187) ----
    // Two tabs of one session share one inbox, and deliverPending hands each pending notification out
    // once: the first tab's poll gets it and merges it into the inbox, the second tab's poll gets
    // nothing. The second tab's bell must still show it. The tabs are real, headless UIs with real bells;
    // the registry hands the poller a stand-in per tab whose access runs inline with that tab current.

    private static final int MEMBER_ID = 42;
    private static final String BADGE_CLASS = "lk-bell-badge";
    private static final NotificationDTO PURCHASE_CONFIRMED = new NotificationDTO("n-1", "PURCHASE_CONFIRMED",
            "SENT", "Your seats are confirmed", LocalDateTime.of(2027, 7, 15, 17, 0));

    private final UI firstTab = new UI();
    private final UI secondTab = new UI();

    @Test
    void GivenTwoTabsAndTheFirstTabsPollDrainsTheInbox_WhenTheSecondTabPollsAndGetsNothing_ThenItsBellShowsTheNotification() {
        AuthSession.storeAuth(new AuthTokenDTO("member-jwt", TOKEN_EXPIRY_EPOCH_MILLIS, MEMBER_ID, "member"));
        NotificationBellComponent firstBell = attachBellTo(firstTab);
        NotificationBellComponent secondBell = attachBellTo(secondTab);
        when(dispatchService.deliverPending(MEMBER_ID)).thenReturn(List.of(PURCHASE_CONFIRMED), List.of());
        registerTabsInPollOrder(firstTab, secondTab);

        scheduler.poll();

        assertEquals(List.of("1"), badgesOf(firstBell), "precondition: the first tab's poll merged the notification");
        assertEquals(List.of("1"), badgesOf(secondBell),
                "the second tab's bell must show what the first tab's poll merged into the shared inbox");
    }

    @Test
    void GivenAnInboxThePollLeavesUnchanged_WhenPoll_ThenTheBellIsNotRendered() {
        AuthSession.storeAuth(new AuthTokenDTO("member-jwt", TOKEN_EXPIRY_EPOCH_MILLIS, MEMBER_ID, "member"));
        NotificationSession.store(List.of(PURCHASE_CONFIRMED));
        NotificationBellComponent bell = attachBellTo(firstTab);
        Component renderedBefore = bell.getChildren().findFirst().orElseThrow();
        // Delivered again although the inbox holds it: the merge drops the duplicate, so nothing changes.
        when(dispatchService.deliverPending(MEMBER_ID)).thenReturn(List.of(PURCHASE_CONFIRMED));
        registerTabsInPollOrder(firstTab);

        scheduler.poll();

        assertSame(renderedBefore, bell.getChildren().findFirst().orElseThrow(),
                "an unchanged inbox must not re-render the bell");
    }

    private static NotificationBellComponent attachBellTo(UI tab) {
        UI.setCurrent(tab);
        NotificationBellComponent bell = new NotificationBellComponent(
                id -> new MarkReadOutcome.Success(), (LocalDateTime createdAt) -> "now");
        tab.add(bell);
        return bell;
    }

    private void registerTabsInPollOrder(UI... tabs) {
        Set<UI> handles = new LinkedHashSet<>();
        for (UI tab : tabs) {
            UI handle = mock(UI.class);
            doAnswer(call -> {
                UI.setCurrent(tab);
                call.getArgument(0, Command.class).execute();
                return null;
            }).when(handle).access(any(Command.class));
            handles.add(handle);
        }
        when(uiRegistry.getActiveUIs()).thenReturn(handles);
    }

    private static List<String> badgesOf(Component root) {
        List<String> badges = new ArrayList<>();
        if (root instanceof HasStyle styled && styled.hasClassName(BADGE_CLASS)) {
            badges.add(root.getElement().getText());
        }
        root.getChildren().forEach(child -> badges.addAll(badgesOf(child)));
        return badges;
    }

    // ui.access runs its command under the UI's session lock on a real UI; here it runs inline,
    // with the map-backed session already current, so AuthSession reads the arranged state.
    private void registerOneUiRunningAccessInline() {
        UI ui = mock(UI.class);
        doAnswer(call -> {
            call.getArgument(0, Command.class).execute();
            return null;
        }).when(ui).access(any(Command.class));
        when(uiRegistry.getActiveUIs()).thenReturn(Set.of(ui));
    }
}
