package com.ticketing.system.unit.presentation;

import static com.ticketing.system.unit.presentation.InboxViewHarness.SESSION_EXPIRED;
import static com.ticketing.system.unit.presentation.InboxViewHarness.badge;
import static com.ticketing.system.unit.presentation.InboxViewHarness.click;
import static com.ticketing.system.unit.presentation.InboxViewHarness.displayTime;
import static com.ticketing.system.unit.presentation.InboxViewHarness.find;
import static com.ticketing.system.unit.presentation.InboxViewHarness.rows;
import static com.ticketing.system.unit.presentation.InboxViewHarness.thread;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.shared.dto.ConversationDTO;
import com.ticketing.system.ui.components.kit.LkSelect;
import com.ticketing.system.ui.presenters.messaging.AdminComplaintQueuePresenter;
import com.ticketing.system.ui.views.admin.AdminComplaintQueueView;

/**
 * {@link AdminComplaintQueueView} with a mocked presenter: which threads the view opens, and what it
 * shows for an open's outcome (#187).
 */
class AdminComplaintQueueViewTest {

    private static final String ALL = "All";
    private static final String OPEN = "Open";

    private final ConversationDTO first = thread("c1", "COMPLAINT", "ADMIN_GROUP");
    private final ConversationDTO second = thread("c2", "COMPLAINT", "ADMIN_GROUP");
    private final AdminComplaintQueuePresenter presenter = mock(AdminComplaintQueuePresenter.class);
    private InboxViewHarness harness;

    @BeforeEach
    void setUp() {
        harness = new InboxViewHarness();
        when(presenter.load(any(), eq(ALL)))
            .thenReturn(new AdminComplaintQueuePresenter.Outcome.Success(List.of(first, second)));
        when(presenter.badge(any(), any())).thenReturn(Optional.of("2"));
        when(presenter.open(any(), any(), any())).thenReturn(new AdminComplaintQueuePresenter.ActionOutcome.Success());
    }

    @AfterEach
    void tearDown() {
        harness.close();
    }

    // ---- Review item 4: only an explicit open marks a thread read ----
    //
    // The view selects a row on its own when it loads and when a filter re-renders the queue; that row
    // is shown, not opened, because an open marks the thread read for the reader's whole side.

    @Test
    void GivenAQueueOfTwoThreads_WhenTheViewIsBuilt_ThenNoThreadIsOpenedAndEveryBadgeStays() {
        AdminComplaintQueueView view = new AdminComplaintQueueView(presenter, displayTime());

        verify(presenter, never()).open(any(), any(), any());
        assertEquals(List.of("2", "2"), rows(view).stream().map(InboxViewHarness::badge).toList());
    }

    @Test
    void GivenAQueueOfTwoThreads_WhenTheReaderClicksTheSecondRow_ThenOnlyThatThreadIsOpened() {
        AdminComplaintQueueView view = new AdminComplaintQueueView(presenter, displayTime());

        click(rows(view).get(1));

        verify(presenter).open(any(), eq(second), any());
        verify(presenter, never()).open(any(), eq(first), any());
    }

    // The filter drops the selected thread, so the view selects the remaining one on its own.
    @Test
    void GivenAQueueOfTwoThreads_WhenAStatusFilterLeavesOnlyTheSecond_ThenTheViewSelectsItWithoutOpeningIt() {
        when(presenter.load(any(), eq(OPEN)))
            .thenReturn(new AdminComplaintQueuePresenter.Outcome.Success(List.of(second)));
        AdminComplaintQueueView view = new AdminComplaintQueueView(presenter, displayTime());
        clearInvocations(presenter);

        find(view, LkSelect.class).get(0).setValue(OPEN);

        verify(presenter).load(any(), eq(OPEN));
        assertEquals(1, rows(view).size());
        verify(presenter, never()).open(any(), any(), any());
    }

    // ---- Review item 3: an open's outcome ----
    //
    // A reader whose session ended is told so, as a response from the respond page tells them; a failed
    // open leaves the badge and tells the reader nothing, since they can do nothing about it.

    @Test
    void GivenTheSessionEnded_WhenTheReaderOpensARow_ThenTheSessionExpiredToastShowsAndTheBadgeStays() {
        when(presenter.open(any(), any(), any()))
            .thenReturn(new AdminComplaintQueuePresenter.ActionOutcome.NotAuthenticated());
        AdminComplaintQueueView view = new AdminComplaintQueueView(presenter, displayTime());

        click(rows(view).get(1));

        assertEquals(List.of(SESSION_EXPIRED), harness.toasts());
        assertEquals("2", badge(rows(view).get(1)));
    }

    @Test
    void GivenTheOpenFails_WhenTheReaderOpensARow_ThenNoToastShowsAndTheBadgeStays() {
        when(presenter.open(any(), any(), any()))
            .thenReturn(new AdminComplaintQueuePresenter.ActionOutcome.Failure("reason"));
        AdminComplaintQueueView view = new AdminComplaintQueueView(presenter, displayTime());

        click(rows(view).get(1));

        assertEquals(List.of(), harness.toasts());
        assertEquals("2", badge(rows(view).get(1)));
    }

    @Test
    void GivenTheOpenSucceeds_WhenTheReaderOpensARow_ThenItsBadgeClearsAndNoToastShows() {
        Set<String> openedIds = new HashSet<>();
        when(presenter.badge(any(), any())).thenAnswer(call -> openedIds.contains(
            call.<ConversationDTO>getArgument(0).conversationId()) ? Optional.empty() : Optional.of("2"));
        when(presenter.open(any(), any(), any())).thenAnswer(call -> {
            openedIds.add(call.<ConversationDTO>getArgument(1).conversationId());
            return new AdminComplaintQueuePresenter.ActionOutcome.Success();
        });
        AdminComplaintQueueView view = new AdminComplaintQueueView(presenter, displayTime());

        click(rows(view).get(1));

        assertNull(badge(rows(view).get(1)));
        assertEquals(List.of(), harness.toasts());
    }
}
