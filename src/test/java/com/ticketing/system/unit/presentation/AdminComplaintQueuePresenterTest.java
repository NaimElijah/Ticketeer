package com.ticketing.system.unit.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.ticketing.system.shared.dto.ComplaintFilterDTO;
import com.ticketing.system.shared.dto.ConversationDTO;
import com.ticketing.system.shared.dto.RespondToComplaintRequestDTO;
import com.ticketing.system.messaging.application.service.MessagingService;
import com.ticketing.system.shared.exception.InvalidTokenException;
import com.ticketing.system.ui.presenters.messaging.AdminComplaintQueuePresenter;
import com.ticketing.system.ui.presenters.messaging.OpenedThreads;
import com.ticketing.system.ui.support.ServiceErrors;
import com.ticketing.system.shared.exception.ConversationClosedException;

class AdminComplaintQueuePresenterTest {

    private static final String TOKEN = "jwt-token";

    private MessagingService messaging;
    private AdminComplaintQueuePresenter presenter;

    @BeforeEach
    void setUp() {
        messaging = mock(MessagingService.class);
        presenter = new AdminComplaintQueuePresenter(messaging);
    }

    private static ConversationDTO complaint(String id, String status) {
        return new ConversationDTO(
            id, "COMPLAINT", status, 1, "MEMBER", 0, "ADMIN_GROUP",
            "Subject " + id, LocalDateTime.now(), LocalDateTime.now(), 0, List.of(),
            "alice", "TicketHub Support");
    }

    // -- load -----------------------------------------------------------------

    @Test
    void load_nullToken_returnsNotAuthenticated_withoutCallingService() {
        AdminComplaintQueuePresenter.Outcome outcome = presenter.load(null, "Open");

        assertInstanceOf(AdminComplaintQueuePresenter.Outcome.NotAuthenticated.class, outcome);
        verify(messaging, never()).viewAllComplaints(anyString(), any());
    }

    @Test
    void load_open_returnsOnlyOpenAndResponded_andQueriesWithoutServerStatus() {
        ConversationDTO open = complaint("c1", "OPEN");
        ConversationDTO responded = complaint("c2", "RESPONDED");
        ConversationDTO resolved = complaint("c3", "RESOLVED");
        ConversationDTO closed = complaint("c4", "CLOSED");
        when(messaging.viewAllComplaints(eq(TOKEN), any()))
            .thenReturn(List.of(open, responded, resolved, closed));

        AdminComplaintQueuePresenter.Outcome.Success ok =
            assertInstanceOf(AdminComplaintQueuePresenter.Outcome.Success.class, presenter.load(TOKEN, "Open"));
        assertEquals(List.of(open, responded), ok.complaints());

        // Grouping is client-side: the service is always queried with no server status filter.
        ArgumentCaptor<ComplaintFilterDTO> captor = ArgumentCaptor.forClass(ComplaintFilterDTO.class);
        verify(messaging).viewAllComplaints(eq(TOKEN), captor.capture());
        assertNull(captor.getValue().status());
    }

    @Test
    void load_resolved_returnsOnlyResolvedAndClosed() {
        ConversationDTO open = complaint("c1", "OPEN");
        ConversationDTO responded = complaint("c2", "RESPONDED");
        ConversationDTO resolved = complaint("c3", "RESOLVED");
        ConversationDTO closed = complaint("c4", "CLOSED");
        when(messaging.viewAllComplaints(eq(TOKEN), any()))
            .thenReturn(List.of(open, responded, resolved, closed));

        AdminComplaintQueuePresenter.Outcome.Success ok =
            assertInstanceOf(AdminComplaintQueuePresenter.Outcome.Success.class, presenter.load(TOKEN, "Resolved"));
        assertEquals(List.of(resolved, closed), ok.complaints());
    }

    @Test
    void load_allFilter_sendsNullStatus() {
        when(messaging.viewAllComplaints(eq(TOKEN), any())).thenReturn(List.of());

        presenter.load(TOKEN, "All");

        ArgumentCaptor<ComplaintFilterDTO> captor = ArgumentCaptor.forClass(ComplaintFilterDTO.class);
        verify(messaging).viewAllComplaints(eq(TOKEN), captor.capture());
        assertNull(captor.getValue().status(), "\"All\" must map to a null (absent) status filter");
    }

    @Test
    void load_nullFilter_sendsNullStatus() {
        when(messaging.viewAllComplaints(eq(TOKEN), any())).thenReturn(List.of());

        presenter.load(TOKEN, null);

        ArgumentCaptor<ComplaintFilterDTO> captor = ArgumentCaptor.forClass(ComplaintFilterDTO.class);
        verify(messaging).viewAllComplaints(eq(TOKEN), captor.capture());
        assertNull(captor.getValue().status());
    }

    @Test
    void load_success_returnsComplaints() {
        ConversationDTO a = complaint("c1", "OPEN");
        ConversationDTO b = complaint("c2", "RESPONDED");
        when(messaging.viewAllComplaints(eq(TOKEN), any())).thenReturn(List.of(a, b));

        AdminComplaintQueuePresenter.Outcome outcome = presenter.load(TOKEN, "All");

        AdminComplaintQueuePresenter.Outcome.Success ok =
            assertInstanceOf(AdminComplaintQueuePresenter.Outcome.Success.class, outcome);
        assertEquals(List.of(a, b), ok.complaints());
    }

    @Test
    void load_invalidToken_returnsNotAuthenticated() {
        when(messaging.viewAllComplaints(eq(TOKEN), any())).thenThrow(new InvalidTokenException("bad"));

        AdminComplaintQueuePresenter.Outcome outcome = presenter.load(TOKEN, "All");

        assertInstanceOf(AdminComplaintQueuePresenter.Outcome.NotAuthenticated.class, outcome);
    }

    @Test
    void load_serviceFailsInternally_returnsGenericFailure() {
        when(messaging.viewAllComplaints(eq(TOKEN), any()))
            .thenThrow(new RuntimeException("not an admin"));

        AdminComplaintQueuePresenter.Outcome outcome = presenter.load(TOKEN, "All");

        AdminComplaintQueuePresenter.Outcome.Failure fail =
            assertInstanceOf(AdminComplaintQueuePresenter.Outcome.Failure.class, outcome);
        assertEquals(ServiceErrors.GENERIC_FAILURE_MESSAGE, fail.reason());
    }

    // -- respond (one-shot) ---------------------------------------------------

    @Test
    void respond_nullToken_returnsNotAuthenticated_withoutCallingService() {
        AdminComplaintQueuePresenter.ActionOutcome outcome = presenter.respond(null, "c1", "resolved");

        assertInstanceOf(AdminComplaintQueuePresenter.ActionOutcome.NotAuthenticated.class, outcome);
        verify(messaging, never()).respondToComplaint(anyString(), any());
    }

    @Test
    void respond_happyPath_returnsSuccess_andSendsBodyWithResolvedStatus() {
        AdminComplaintQueuePresenter.ActionOutcome outcome =
            presenter.respond(TOKEN, "c1", "Refund issued — resolved.");

        assertInstanceOf(AdminComplaintQueuePresenter.ActionOutcome.Success.class, outcome);
        ArgumentCaptor<RespondToComplaintRequestDTO> captor =
            ArgumentCaptor.forClass(RespondToComplaintRequestDTO.class);
        verify(messaging).respondToComplaint(eq(TOKEN), captor.capture());
        assertEquals("c1", captor.getValue().conversationId());
        assertEquals("Refund issued — resolved.", captor.getValue().body());
        assertEquals("RESOLVED", captor.getValue().newStatus(), "the single response resolves the complaint");
    }

    @Test
    void respond_serviceFailsInternally_returnsGenericFailure() {
        doThrow(new RuntimeException("already resolved"))
            .when(messaging).respondToComplaint(anyString(), any());

        AdminComplaintQueuePresenter.ActionOutcome outcome = presenter.respond(TOKEN, "c1", "late");

        AdminComplaintQueuePresenter.ActionOutcome.Failure fail =
            assertInstanceOf(AdminComplaintQueuePresenter.ActionOutcome.Failure.class, outcome);
        assertEquals(ServiceErrors.GENERIC_FAILURE_MESSAGE, fail.reason());
    }

    @Test
    void respond_invalidToken_returnsNotAuthenticated() {
        doThrow(new InvalidTokenException("bad"))
            .when(messaging).respondToComplaint(anyString(), any());

        AdminComplaintQueuePresenter.ActionOutcome outcome = presenter.respond(TOKEN, "c1", "hi");

        assertInstanceOf(AdminComplaintQueuePresenter.ActionOutcome.NotAuthenticated.class, outcome);
    }

    // -- loadOne --------------------------------------------------------------

    @Test
    void loadOne_nullToken_returnsNotAuthenticated_withoutCallingService() {
        AdminComplaintQueuePresenter.SingleOutcome outcome = presenter.loadOne(null, "c1");

        assertInstanceOf(AdminComplaintQueuePresenter.SingleOutcome.NotAuthenticated.class, outcome);
        verify(messaging, never()).viewConversation(anyString(), anyString());
    }

    @Test
    void loadOne_success_returnsConversation() {
        ConversationDTO c = complaint("c1", "OPEN");
        when(messaging.viewConversation(TOKEN, "c1")).thenReturn(c);

        AdminComplaintQueuePresenter.SingleOutcome outcome = presenter.loadOne(TOKEN, "c1");

        AdminComplaintQueuePresenter.SingleOutcome.Success ok =
            assertInstanceOf(AdminComplaintQueuePresenter.SingleOutcome.Success.class, outcome);
        assertEquals(c, ok.conversation());
    }

    @Test
    void loadOne_invalidToken_returnsNotAuthenticated() {
        when(messaging.viewConversation(eq(TOKEN), anyString())).thenThrow(new InvalidTokenException("bad"));

        AdminComplaintQueuePresenter.SingleOutcome outcome = presenter.loadOne(TOKEN, "c1");

        assertInstanceOf(AdminComplaintQueuePresenter.SingleOutcome.NotAuthenticated.class, outcome);
    }

    @Test
    void respond_conversationClosed_returnsFailureWithTheRulesMessage() {
        ConversationClosedException rule = new ConversationClosedException("c1");
        doThrow(rule).when(messaging).respondToComplaint(anyString(), any());

        AdminComplaintQueuePresenter.ActionOutcome.Failure fail = assertInstanceOf(
            AdminComplaintQueuePresenter.ActionOutcome.Failure.class, presenter.respond(TOKEN, "c1", "late"));
        assertEquals(rule.getMessage(), fail.reason());
    }

    // ---- Nothing ever marks a conversation read (#187) ----

    @Test
    void GivenAComplaintThread_WhenTheAdminOpensIt_ThenTheThreadIsMarkedRead() {
        when(messaging.viewConversation(TOKEN, "c1")).thenReturn(complaint("c1", "OPEN"));

        presenter.loadOne(TOKEN, "c1");

        verify(messaging).markConversationRead(TOKEN, "c1");
    }

    @Test
    void GivenAThreadShownInTheDetailCard_WhenMarkRead_ThenTheThreadIsMarkedRead() {
        AdminComplaintQueuePresenter.ActionOutcome outcome = presenter.markRead(TOKEN, "c1");

        assertInstanceOf(AdminComplaintQueuePresenter.ActionOutcome.Success.class, outcome);
        verify(messaging).markConversationRead(TOKEN, "c1");
    }

    @Test
    void GivenNoToken_WhenMarkRead_ThenNotAuthenticatedAndNothingIsMarked() {
        assertInstanceOf(AdminComplaintQueuePresenter.ActionOutcome.NotAuthenticated.class, presenter.markRead(null, "c1"));

        verify(messaging, never()).markConversationRead(anyString(), anyString());
    }

    @Test
    void GivenAnExpiredToken_WhenMarkRead_ThenNotAuthenticated() {
        doThrow(new InvalidTokenException("bad")).when(messaging).markConversationRead(anyString(), anyString());

        assertInstanceOf(AdminComplaintQueuePresenter.ActionOutcome.NotAuthenticated.class, presenter.markRead(TOKEN, "c1"));
    }

    @Test
    void GivenTheMarkFailsInternally_WhenMarkRead_ThenTheReasonIsTheGenericText() {
        doThrow(new RuntimeException("could not execute statement [update messages set is_read=?]"))
            .when(messaging).markConversationRead(anyString(), anyString());

        AdminComplaintQueuePresenter.ActionOutcome.Failure fail =
            assertInstanceOf(AdminComplaintQueuePresenter.ActionOutcome.Failure.class, presenter.markRead(TOKEN, "c1"));
        assertEquals(ServiceErrors.GENERIC_FAILURE_MESSAGE, fail.reason());
    }

    // ---- Mark read only on an explicit open (#187) ----
    //
    // A thread is marked read when the reader opens it, never when the view merely shows it, because a
    // mark reads it for the reader's whole side. The view keeps an OpenedThreads; the presenter decides.

    private static final LocalDateTime LAST_MESSAGE_AT = LocalDateTime.of(2027, 7, 15, 17, 0);

    private static ConversationDTO unreadThread(String id, int unread, LocalDateTime lastMessageAt) {
        return new ConversationDTO(id, "COMPLAINT", "OPEN", 1, "MEMBER", 7, "COMPANY", "Subject " + id,
                LAST_MESSAGE_AT, lastMessageAt, unread, List.of(), "alice", "Acme");
    }

    @Test
    void GivenAnUnreadThread_WhenTheAdminOpensIt_ThenItIsMarkedReadAndItsBadgeIsCleared() {
        OpenedThreads opened = new OpenedThreads();
        ConversationDTO thread = unreadThread("c1", 2, LAST_MESSAGE_AT);
        assertEquals(Optional.of("2"), presenter.badge(thread, opened));

        AdminComplaintQueuePresenter.ActionOutcome outcome = presenter.open(TOKEN, thread, opened);

        assertInstanceOf(AdminComplaintQueuePresenter.ActionOutcome.Success.class, outcome);
        verify(messaging).markConversationRead(TOKEN, "c1");
        assertEquals(Optional.empty(), presenter.badge(thread, opened));
    }

    @Test
    void GivenALoadedInbox_WhenItsRowsAreRenderedWithoutAnOpen_ThenNothingIsMarkedReadAndTheBadgeStays() {
        ConversationDTO thread = unreadThread("c1", 2, LAST_MESSAGE_AT);
        when(messaging.viewAllComplaints(eq(TOKEN), any())).thenReturn(List.of(thread));
        List<ConversationDTO> listed = assertInstanceOf(AdminComplaintQueuePresenter.Outcome.Success.class,
                presenter.load(TOKEN, "All")).complaints();

        List<Optional<String>> badges = listed.stream().map(c -> presenter.badge(c, new OpenedThreads())).toList();

        assertEquals(List.of(Optional.of("2")), badges);
        verify(messaging, never()).markConversationRead(anyString(), anyString());
    }

    @Test
    void GivenAnOpenedThread_WhenTheSameListIsRenderedAgain_ThenItsBadgeStaysCleared() {
        OpenedThreads opened = new OpenedThreads();
        ConversationDTO thread = unreadThread("c1", 2, LAST_MESSAGE_AT);
        presenter.open(TOKEN, thread, opened);

        ConversationDTO sameThreadRenderedAgain = unreadThread("c1", 2, LAST_MESSAGE_AT);

        assertEquals(Optional.empty(), presenter.badge(sameThreadRenderedAgain, opened));
    }

    @Test
    void GivenAnOpenedThreadThatGetsANewerMessage_WhenTheListIsReloaded_ThenItsBadgeShowsTheNewCount() {
        OpenedThreads opened = new OpenedThreads();
        presenter.open(TOKEN, unreadThread("c1", 2, LAST_MESSAGE_AT), opened);

        ConversationDTO reloaded = unreadThread("c1", 1, LAST_MESSAGE_AT.plusMinutes(5));

        assertEquals(Optional.of("1"), presenter.badge(reloaded, opened));
    }

    @Test
    void GivenAThreadWithNothingUnread_WhenItsRowIsRendered_ThenItHasNoBadge() {
        assertEquals(Optional.empty(), presenter.badge(unreadThread("c1", 0, LAST_MESSAGE_AT), new OpenedThreads()));
    }

    @Test
    void GivenTheMarkFailsInternally_WhenOpened_ThenTheGenericReasonAndTheBadgeStays() {
        OpenedThreads opened = new OpenedThreads();
        ConversationDTO thread = unreadThread("c1", 2, LAST_MESSAGE_AT);
        doThrow(new RuntimeException("could not execute statement [update messages set is_read=?]"))
            .when(messaging).markConversationRead(anyString(), anyString());

        AdminComplaintQueuePresenter.ActionOutcome.Failure fail = assertInstanceOf(AdminComplaintQueuePresenter.ActionOutcome.Failure.class,
                presenter.open(TOKEN, thread, opened));

        assertEquals(ServiceErrors.GENERIC_FAILURE_MESSAGE, fail.reason());
        assertEquals(Optional.of("2"), presenter.badge(thread, opened));
    }

    @Test
    void GivenNoToken_WhenOpened_ThenNotAuthenticatedNothingIsMarkedAndTheBadgeStays() {
        OpenedThreads opened = new OpenedThreads();
        ConversationDTO thread = unreadThread("c1", 2, LAST_MESSAGE_AT);

        assertInstanceOf(AdminComplaintQueuePresenter.ActionOutcome.NotAuthenticated.class, presenter.open(null, thread, opened));

        verify(messaging, never()).markConversationRead(anyString(), anyString());
        assertEquals(Optional.of("2"), presenter.badge(thread, opened));
    }

    @Test
    void GivenAnExpiredToken_WhenOpened_ThenNotAuthenticatedAndTheBadgeStays() {
        OpenedThreads opened = new OpenedThreads();
        ConversationDTO thread = unreadThread("c1", 2, LAST_MESSAGE_AT);
        doThrow(new InvalidTokenException("bad")).when(messaging).markConversationRead(anyString(), anyString());

        assertInstanceOf(AdminComplaintQueuePresenter.ActionOutcome.NotAuthenticated.class, presenter.open(TOKEN, thread, opened));

        assertEquals(Optional.of("2"), presenter.badge(thread, opened));
    }
}
