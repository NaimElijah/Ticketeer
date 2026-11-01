package com.ticketing.system.unit.infrastructure.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import ch.qos.logback.classic.Level;

import com.ticketing.system.bootstrap.scheduling.ActiveUiRegistry;
import com.ticketing.system.bootstrap.scheduling.NotificationPollingScheduler;
import com.ticketing.system.identity.application.service.AuthenticationService;
import com.ticketing.system.notifications.application.service.NotificationDispatchService;
import com.ticketing.system.shared.dto.AuthTokenDTO;
import com.ticketing.system.testutil.LogCapture;
import com.ticketing.system.ui.session.AuthSession;
import com.ticketing.system.ui.session.FreshGuestSession;
import com.ticketing.system.ui.session.MapBackedVaadinSession;
import com.ticketing.system.ui.session.SessionIdentity;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.UIDetachedException;
import com.vaadin.flow.server.Command;

/**
 * The poller goes on to the next UI when one has detached, logs a failed delivery with its exception,
 * and delivers only to a member whose token is still live, checked the way {@code AuthBootstrap}
 * checks it (#187).
 *
 * <p>The poller is built by a small Spring context from its collaborators, so it can take whichever
 * of them it needs: the liveness check reads {@link AuthenticationService#liveMemberId}, as
 * {@link SessionIdentity} does. As in {@code NotificationPollingSchedulerTest}, Vaadin's {@link UI} is
 * a test double whose {@code access} runs the command inline on a map-backed Vaadin session.
 */
class NotificationPollingSchedulerDeliveryTest {

    // ---- Defect 9: the poller skips detached UIs and dead tokens (#187) ----

    private static final int MEMBER_ID = 1;
    private static final String LIVE_TOKEN = "live-member-jwt";
    private static final String DEAD_TOKEN = "dead-member-jwt";
    private static final long TOKEN_EXPIRY_EPOCH_MILLIS = 0L;

    private final ActiveUiRegistry uiRegistry = mock(ActiveUiRegistry.class);
    private final NotificationDispatchService dispatchService = mock(NotificationDispatchService.class);
    private final AuthenticationService authenticationService = mock(AuthenticationService.class);
    private LogCapture logs;

    @BeforeEach
    void setUp() {
        MapBackedVaadinSession.install();
        when(dispatchService.deliverPending(anyInt())).thenReturn(List.of());
        when(authenticationService.liveMemberId(LIVE_TOKEN)).thenReturn(OptionalInt.of(MEMBER_ID));
        when(authenticationService.liveMemberId(DEAD_TOKEN)).thenReturn(OptionalInt.empty());
        logs = LogCapture.of(NotificationPollingScheduler.class);
    }

    @AfterEach
    void tearDown() {
        logs.close();
        MapBackedVaadinSession.uninstall();
    }

    @Test
    void GivenTheFirstUiHasDetached_WhenPoll_ThenTheSecondUiStillGetsItsNotifications() {
        AuthSession.storeAuth(memberToken(LIVE_TOKEN));
        UI detached = mock(UI.class);
        doThrow(new UIDetachedException()).when(detached).access(any(Command.class));
        activeUis(detached, uiRunningAccessInline());

        withPoller(poller -> {
            assertDoesNotThrow(poller::poll, "a detached UI must not abort the poll");

            verify(dispatchService).deliverPending(MEMBER_ID);
        });
    }

    @Test
    void GivenMemberUiWhoseTokenIsDead_WhenPoll_ThenNothingIsDelivered() {
        AuthSession.storeAuth(memberToken(DEAD_TOKEN));
        activeUis(uiRunningAccessInline());

        withPoller(poller -> {
            poller.poll();

            verify(dispatchService, never()).deliverPending(anyInt());
        });
    }

    @Test
    void GivenMemberUiWhoseTokenIsLive_WhenPoll_ThenTheMembersNotificationsAreDelivered() {
        AuthSession.storeAuth(memberToken(LIVE_TOKEN));
        activeUis(uiRunningAccessInline());

        withPoller(poller -> {
            poller.poll();

            verify(dispatchService).deliverPending(MEMBER_ID);
        });
    }

    @Test
    void GivenDeliveryFails_WhenPoll_ThenTheFailureIsLoggedWithItsException() {
        AuthSession.storeAuth(memberToken(LIVE_TOKEN));
        activeUis(uiRunningAccessInline());
        when(dispatchService.deliverPending(MEMBER_ID)).thenThrow(new IllegalStateException("dispatch store unavailable"));

        withPoller(poller -> {
            poller.poll();

            List<String> attached = new ArrayList<>(logs.throwableClassNamesAt(Level.WARN));
            attached.addAll(logs.throwableClassNamesAt(Level.ERROR));
            assertTrue(attached.contains(IllegalStateException.class.getName()),
                    "the failed delivery must be logged with its exception attached, but the attached throwables were "
                            + attached);
        });
    }

    private void withPoller(Consumer<NotificationPollingScheduler> test) {
        new ApplicationContextRunner()
                .withBean(ActiveUiRegistry.class, () -> uiRegistry)
                .withBean(NotificationDispatchService.class, () -> dispatchService)
                .withBean(AuthenticationService.class, () -> authenticationService)
                .withBean(FreshGuestSession.class)
                .withBean(SessionIdentity.class)
                .withBean(NotificationPollingScheduler.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    test.accept(context.getBean(NotificationPollingScheduler.class));
                });
    }

    private void activeUis(UI... uis) {
        when(uiRegistry.getActiveUIs()).thenReturn(new LinkedHashSet<>(List.of(uis)));
    }

    // ui.access runs its command under the UI's session lock on a real UI; here it runs inline, with the
    // map-backed session already current, so AuthSession reads the arranged state.
    private static UI uiRunningAccessInline() {
        UI ui = mock(UI.class);
        doAnswer(call -> {
            call.getArgument(0, Command.class).execute();
            return null;
        }).when(ui).access(any(Command.class));
        return ui;
    }

    private static AuthTokenDTO memberToken(String token) {
        return new AuthTokenDTO(token, TOKEN_EXPIRY_EPOCH_MILLIS, MEMBER_ID, "member");
    }
}
