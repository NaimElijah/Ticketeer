package com.ticketing.system.unit.infrastructure.scheduling;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.bootstrap.scheduling.ActiveUiRegistry;
import com.ticketing.system.bootstrap.scheduling.NotificationPollingScheduler;
import com.ticketing.system.notifications.application.service.NotificationDispatchService;
import com.ticketing.system.shared.dto.AuthTokenDTO;
import com.ticketing.system.ui.session.AuthSession;
import com.ticketing.system.ui.session.MapBackedVaadinSession;
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
