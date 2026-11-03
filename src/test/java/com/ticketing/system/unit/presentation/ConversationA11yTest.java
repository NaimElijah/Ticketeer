package com.ticketing.system.unit.presentation;

import static com.ticketing.system.unit.presentation.InboxViewHarness.displayTime;
import static com.ticketing.system.unit.presentation.InboxViewHarness.rows;
import static com.ticketing.system.unit.presentation.InboxViewHarness.thread;
import static com.ticketing.system.unit.presentation.ViewHarness.click;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.shared.dto.MyCompanyDTO;
import com.ticketing.system.ui.components.messaging.MdConvRow;
import com.ticketing.system.ui.components.messaging.MdThread;
import com.ticketing.system.ui.presenters.messaging.AdminComplaintQueuePresenter;
import com.ticketing.system.ui.presenters.messaging.AdminInboxPresenter;
import com.ticketing.system.ui.presenters.messaging.CompanyInquiryInboxPresenter;
import com.ticketing.system.ui.presenters.messaging.SupportInboxPresenter;
import com.ticketing.system.ui.views.account.SupportInboxView;
import com.ticketing.system.ui.views.admin.AdminComplaintQueueView;
import com.ticketing.system.ui.views.admin.AdminInboxView;
import com.ticketing.system.ui.views.company.CompanyInquiryInboxView;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.dom.Element;

/**
 * The inboxes say, in words a screen reader reads, which conversation is open, how many messages are
 * unread and whether a thread is closed, and they take the reader to the conversation they chose (#188,
 * A-m2, U-A19, P-m6; WCAG 1.3.1, 1.4.1, 2.4.3, 4.1.2). The selected row is marked current, not only
 * tinted; the unread badge reads as a count of unread messages, not a bare number; a closed thread says
 * so rather than only being dimmed; the thread is a log a keyboard can reach and scroll; and choosing a
 * conversation moves focus to its heading.
 */
class ConversationA11yTest {

    // ---- F19: conversations (#188) ----

    private ViewHarness harness;

    @BeforeEach
    void setUp() {
        harness = new ViewHarness();
    }

    @AfterEach
    void tearDown() {
        harness.close();
    }

    // ---- MdConvRow ----

    @Test
    void GivenASelectedConversationRow_WhenItRenders_ThenItIsTheCurrentRow() {
        MdConvRow row = new MdConvRow("comment", "Refund delay", "alice", "1h", null).active();

        String current = row.getElement().getAttribute("aria-current");
        assertTrue(current != null && !current.isBlank() && !"false".equals(current),
            "the selected row must say so with aria-current, not only by its tint: " + A11y.describe(row.getElement()));
    }

    @Test
    void GivenASelectedRow_WhenAnotherRowIsSelected_ThenItIsNoLongerCurrent() {
        MdConvRow row = new MdConvRow("comment", "Refund delay", "alice", "1h", null).active();

        row.active(false);

        String current = row.getElement().getAttribute("aria-current");
        assertTrue(current == null || "false".equals(current),
            "a row no longer selected must not stay current: " + A11y.describe(row.getElement()));
    }

    @Test
    void GivenARowWithUnreadMessages_WhenItRenders_ThenItsNameSaysHowManyAreUnread() {
        MdConvRow row = new MdConvRow("comment", "Refund delay", "alice", "1h", "2");

        String name = A11y.name(row.getElement());
        assertTrue(Pattern.compile("(?i)\\b2 unread\\b").matcher(name).find(),
            "the unread badge must be read as \"2 unread\", not as a bare \"2\"; the row is named \"" + name + "\"");
    }

    @Test
    void GivenAClosedOutreachThread_WhenTheMemberOpensTheInbox_ThenItsRowSaysClosedInWords() {
        ViewHarness.signInMember();
        SupportInboxPresenter presenter = mock(SupportInboxPresenter.class);
        when(presenter.load(any())).thenReturn(new SupportInboxPresenter.Outcome.Success(List.of(
            thread("c1", "DIRECT", "MEMBER"), thread("c2", "DIRECT", "MEMBER", "CLOSED"))));
        when(presenter.badge(any(), any())).thenReturn(Optional.empty());
        SupportInboxView view = new SupportInboxView(presenter, displayTime());

        String name = A11y.name(rows(view).get(1).getElement());
        assertTrue(Pattern.compile("(?i)\\bclosed\\b").matcher(name).find(),
            "a closed thread must say \"Closed\" in its row, not only be dimmed; the row is named \"" + name + "\"");
    }

    // ---- MdThread ----

    @Test
    void GivenAThread_WhenItRenders_ThenItIsANamedFocusableLog() {
        MdThread thread = new MdThread(List.of(new MdThread.Message("alice", "1h", false, "Where's my refund?")));
        Element e = thread.getElement();

        assertEquals("log", e.getAttribute("role"), "the thread must be a log, so new messages are read in order: "
            + A11y.describe(e));
        assertEquals("0", e.getAttribute("tabindex"), "the scrolling thread must be reachable with Tab, so a "
            + "keyboard can scroll it: " + A11y.describe(e));
        assertFalse(A11y.name(e).isBlank(), "the thread must be named (aria-label): " + A11y.describe(e));
    }

    // ---- choosing a conversation moves focus to its heading ----

    @Test
    void GivenTheSupportInbox_WhenTheMemberChoosesAConversation_ThenFocusMovesToItsHeading() {
        ViewHarness.signInMember();
        SupportInboxPresenter presenter = mock(SupportInboxPresenter.class);
        when(presenter.load(any())).thenReturn(new SupportInboxPresenter.Outcome.Success(List.of(
            thread("c1", "INQUIRY", "COMPANY"), thread("c2", "INQUIRY", "COMPANY"))));
        when(presenter.badge(any(), any())).thenReturn(Optional.empty());
        when(presenter.open(any(), any(), any())).thenReturn(new SupportInboxPresenter.ActionOutcome.Success());
        SupportInboxView view = new SupportInboxView(presenter, displayTime());

        assertChoosingMovesFocusToHeading(view, "Subject c2");
    }

    @Test
    void GivenTheAdminInbox_WhenTheAdminChoosesAConversation_ThenFocusMovesToItsHeading() {
        ViewHarness.signInAdmin();
        AdminInboxPresenter presenter = mock(AdminInboxPresenter.class);
        when(presenter.load(any())).thenReturn(new AdminInboxPresenter.Outcome.Success(List.of(
            thread("c1", "DIRECT", "MEMBER"), thread("c2", "DIRECT", "MEMBER"))));
        when(presenter.badge(any(), any())).thenReturn(Optional.empty());
        when(presenter.open(any(), any(), any())).thenReturn(new AdminInboxPresenter.ActionOutcome.Success());
        AdminInboxView view = new AdminInboxView(presenter, displayTime());

        assertChoosingMovesFocusToHeading(view, "Subject c2");
    }

    @Test
    void GivenTheCompanyInbox_WhenTheOwnerChoosesAnInquiry_ThenFocusMovesToItsHeading() {
        ViewHarness.signInMember();
        CompanyInquiryInboxPresenter presenter = mock(CompanyInquiryInboxPresenter.class);
        MyCompanyDTO company = new MyCompanyDTO(7, "Acme", "Founder");
        when(presenter.loadFor(any(), any())).thenReturn(new CompanyInquiryInboxPresenter.Outcome.Success(
            List.of(company), company, List.of(thread("c1", "INQUIRY", "COMPANY"), thread("c2", "INQUIRY", "COMPANY"))));
        when(presenter.badge(any(), any())).thenReturn(Optional.empty());
        when(presenter.open(any(), any(), any())).thenReturn(new CompanyInquiryInboxPresenter.ActionOutcome.Success());
        CompanyInquiryInboxView view = new CompanyInquiryInboxView(presenter, displayTime());

        assertChoosingMovesFocusToHeading(view, "Subject c2");
    }

    @Test
    void GivenTheComplaintQueue_WhenTheAdminChoosesAComplaint_ThenFocusMovesToItsHeading() {
        ViewHarness.signInAdmin();
        AdminComplaintQueuePresenter presenter = mock(AdminComplaintQueuePresenter.class);
        when(presenter.load(any(), any())).thenReturn(new AdminComplaintQueuePresenter.Outcome.Success(List.of(
            thread("c1", "COMPLAINT", "ADMIN_GROUP"), thread("c2", "COMPLAINT", "ADMIN_GROUP"))));
        when(presenter.badge(any(), any())).thenReturn(Optional.empty());
        when(presenter.open(any(), any(), any())).thenReturn(new AdminComplaintQueuePresenter.ActionOutcome.Success());
        AdminComplaintQueueView view = new AdminComplaintQueueView(presenter, displayTime());

        assertChoosingMovesFocusToHeading(view, "Subject c2");
    }

    // ---- assertions ------------------------------------------------------------------------------

    private void assertChoosingMovesFocusToHeading(Component view, String subject) {
        harness.attach(view);
        int before = harness.focusTargets().size();

        click(rows(view).get(1));

        List<Element> all = harness.focusTargets();
        List<Element> focused = all.subList(before, all.size());
        assertTrue(focused.stream().anyMatch(t -> A11y.within(t, view.getElement())
                && "heading".equals(A11y.role(t)) && A11y.text(t).contains(subject)),
            "choosing a conversation must move focus to the heading of its detail, \"" + subject + "\", so the "
                + "swap is announced; the focus calls were " + focused.stream().map(A11y::describe).toList());
    }
}
