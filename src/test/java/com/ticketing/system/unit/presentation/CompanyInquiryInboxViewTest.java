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
import com.ticketing.system.shared.dto.MyCompanyDTO;
import com.ticketing.system.ui.components.kit.LkFilterChip;
import com.ticketing.system.ui.presenters.messaging.CompanyInquiryInboxPresenter;
import com.ticketing.system.ui.views.company.CompanyInquiryInboxView;
import com.vaadin.flow.component.html.NativeButton;

/**
 * {@link CompanyInquiryInboxView} with a mocked presenter: which threads the view opens, and what it
 * shows for an open's outcome (#187).
 */
class CompanyInquiryInboxViewTest {

    private final MyCompanyDTO company = new MyCompanyDTO(7, "Acme", "Founder");
    private final ConversationDTO first = thread("c1", "INQUIRY", "COMPANY", "CLOSED");
    private final ConversationDTO second = thread("c2", "INQUIRY", "COMPANY", "OPEN");
    private final CompanyInquiryInboxPresenter presenter = mock(CompanyInquiryInboxPresenter.class);
    private InboxViewHarness harness;

    @BeforeEach
    void setUp() {
        harness = new InboxViewHarness();
        when(presenter.loadFor(any(), any())).thenReturn(
            new CompanyInquiryInboxPresenter.Outcome.Success(List.of(company), company, List.of(first, second)));
        when(presenter.badge(any(), any())).thenReturn(Optional.of("2"));
        when(presenter.open(any(), any(), any())).thenReturn(new CompanyInquiryInboxPresenter.ActionOutcome.Success());
    }

    @AfterEach
    void tearDown() {
        harness.close();
    }

    // ---- Review item 4: only an explicit open marks a thread read ----
    //
    // The view selects a row on its own when it loads and when the status filter re-renders the list;
    // that row is shown, not opened, because an open marks the thread read for the reader's whole side.

    @Test
    void GivenAnInboxOfTwoThreads_WhenTheViewIsBuilt_ThenNoThreadIsOpenedAndEveryBadgeStays() {
        CompanyInquiryInboxView view = new CompanyInquiryInboxView(presenter, displayTime());

        verify(presenter, never()).open(any(), any(), any());
        assertEquals(List.of("2", "2"), rows(view).stream().map(InboxViewHarness::badge).toList());
    }

    @Test
    void GivenAnInboxOfTwoThreads_WhenTheReaderClicksTheSecondRow_ThenOnlyThatThreadIsOpened() {
        CompanyInquiryInboxView view = new CompanyInquiryInboxView(presenter, displayTime());

        click(rows(view).get(1));

        verify(presenter).open(any(), eq(second), any());
        verify(presenter, never()).open(any(), eq(first), any());
    }

    // The filter hides the selected (closed) thread, so the view selects the open one on its own.
    @Test
    void GivenAnInboxOfTwoThreads_WhenTheStatusFilterLeavesOnlyTheSecond_ThenTheViewSelectsItWithoutOpeningIt() {
        CompanyInquiryInboxView view = new CompanyInquiryInboxView(presenter, displayTime());
        clearInvocations(presenter);

        applyStatusFilter(view, "Open");

        assertEquals(1, rows(view).size());
        verify(presenter, never()).open(any(), any(), any());
    }

    // ---- Review item 3: an open's outcome ----
    //
    // A reader whose session ended is told so, as a reply from the respond page tells them; a failed
    // open leaves the badge and tells the reader nothing, since they can do nothing about it.

    @Test
    void GivenTheSessionEnded_WhenTheReaderOpensARow_ThenTheSessionExpiredToastShowsAndTheBadgeStays() {
        when(presenter.open(any(), any(), any()))
            .thenReturn(new CompanyInquiryInboxPresenter.ActionOutcome.NotAuthenticated());
        CompanyInquiryInboxView view = new CompanyInquiryInboxView(presenter, displayTime());

        click(rows(view).get(1));

        assertEquals(List.of(SESSION_EXPIRED), harness.toasts());
        assertEquals("2", badge(rows(view).get(1)));
    }

    @Test
    void GivenTheOpenFails_WhenTheReaderOpensARow_ThenNoToastShowsAndTheBadgeStays() {
        when(presenter.open(any(), any(), any()))
            .thenReturn(new CompanyInquiryInboxPresenter.ActionOutcome.Failure("reason"));
        CompanyInquiryInboxView view = new CompanyInquiryInboxView(presenter, displayTime());

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
            return new CompanyInquiryInboxPresenter.ActionOutcome.Success();
        });
        CompanyInquiryInboxView view = new CompanyInquiryInboxView(presenter, displayTime());

        click(rows(view).get(1));

        assertNull(badge(rows(view).get(1)));
        assertEquals(List.of(), harness.toasts());
    }

    // Picks the option in the Status chip's menu, then presses its Apply button, as the reader does.
    private static void applyStatusFilter(CompanyInquiryInboxView view, String option) {
        click(menuButton(view, option));
        click(menuButton(view, "Apply"));
    }

    private static NativeButton menuButton(CompanyInquiryInboxView view, String text) {
        LkFilterChip chip = find(view, LkFilterChip.class).get(0);
        return find(chip, NativeButton.class).stream()
            .filter(button -> text.equals(button.getElement().getTextRecursively()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no '" + text + "' button in the Status chip"));
    }
}
