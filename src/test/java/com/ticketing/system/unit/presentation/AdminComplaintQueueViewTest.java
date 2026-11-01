package com.ticketing.system.unit.presentation;

import static com.ticketing.system.unit.presentation.InboxViewHarness.SESSION_EXPIRED;
import static com.ticketing.system.unit.presentation.InboxViewHarness.badge;
import static com.ticketing.system.unit.presentation.InboxViewHarness.click;
import static com.ticketing.system.unit.presentation.InboxViewHarness.displayTime;
import static com.ticketing.system.unit.presentation.InboxViewHarness.rows;
import static com.ticketing.system.unit.presentation.InboxViewHarness.thread;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.shared.dto.ConversationDTO;
import com.ticketing.system.ui.presenters.messaging.AdminComplaintQueuePresenter;
import com.ticketing.system.ui.views.admin.AdminComplaintQueueView;

/**
 * {@link AdminComplaintQueueView} with a mocked presenter: which threads the view opens, and what it
 * shows for an open's outcome (#187).
 */
class AdminComplaintQueueViewTest {

    private static final String ALL = "All";

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
