package com.ticketing.system.unit.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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

import com.ticketing.system.shared.dto.ConversationDTO;
import com.ticketing.system.shared.dto.SendMessageRequestDTO;
import com.ticketing.system.messaging.application.service.MessagingService;
import com.ticketing.system.shared.exception.InvalidTokenException;
import com.ticketing.system.ui.presenters.messaging.AdminInboxPresenter;
import com.ticketing.system.ui.presenters.messaging.OpenedThreads;
import com.ticketing.system.ui.support.ServiceErrors;
import com.ticketing.system.shared.exception.ConversationClosedException;

class AdminInboxPresenterTest {

    private static final String TOKEN = "jwt-token";

    private MessagingService messaging;
    private AdminInboxPresenter presenter;

    @BeforeEach
    void setUp() {
        messaging = mock(MessagingService.class);
        presenter = new AdminInboxPresenter(messaging);
    }

    private static ConversationDTO direct(String id) {
        return new ConversationDTO(
            id, "DIRECT", "OPEN", 1, "ADMIN", 7, "MEMBER",
            "Heads up", LocalDateTime.now(), LocalDateTime.now(), 0, List.of(),
            "TicketHub Support", "alice");
    }

    // -- load -----------------------------------------------------------------

    @Test
    void load_nullToken_returnsNotAuthenticated_withoutCallingService() {
        AdminInboxPresenter.Outcome outcome = presenter.load(null);

        assertInstanceOf(AdminInboxPresenter.Outcome.NotAuthenticated.class, outcome);
        verify(messaging, never()).viewAdminInbox(anyString());
    }

    @Test
    void load_success_returnsConversations() {
        ConversationDTO a = direct("c1");
        when(messaging.viewAdminInbox(TOKEN)).thenReturn(List.of(a));

        AdminInboxPresenter.Outcome outcome = presenter.load(TOKEN);

        AdminInboxPresenter.Outcome.Success ok =
            assertInstanceOf(AdminInboxPresenter.Outcome.Success.class, outcome);
        assertEquals(List.of(a), ok.conversations());
    }

    @Test
    void load_invalidToken_returnsNotAuthenticated() {
        when(messaging.viewAdminInbox(TOKEN)).thenThrow(new InvalidTokenException("bad"));

        assertInstanceOf(AdminInboxPresenter.Outcome.NotAuthenticated.class, presenter.load(TOKEN));
    }

    @Test
    void load_serviceFailsInternally_returnsGenericFailure() {
        when(messaging.viewAdminInbox(TOKEN)).thenThrow(new RuntimeException("backend down"));

        AdminInboxPresenter.Outcome.Failure fail =
            assertInstanceOf(AdminInboxPresenter.Outcome.Failure.class, presenter.load(TOKEN));
        assertEquals(ServiceErrors.GENERIC_FAILURE_MESSAGE, fail.reason());
    }

    // -- reply ----------------------------------------------------------------

    @Test
    void reply_nullToken_returnsNotAuthenticated_withoutCallingService() {
        AdminInboxPresenter.ActionOutcome outcome = presenter.reply(null, "c1", "hi");

        assertInstanceOf(AdminInboxPresenter.ActionOutcome.NotAuthenticated.class, outcome);
        verify(messaging, never()).sendMessage(anyString(), any());
    }

    @Test
    void reply_happyPath_sendsConversationIdAndBody() {
        AdminInboxPresenter.ActionOutcome outcome = presenter.reply(TOKEN, "c1", "any update?");

        assertInstanceOf(AdminInboxPresenter.ActionOutcome.Success.class, outcome);
        ArgumentCaptor<SendMessageRequestDTO> captor = ArgumentCaptor.forClass(SendMessageRequestDTO.class);
        verify(messaging).sendMessage(eq(TOKEN), captor.capture());
        assertEquals("c1", captor.getValue().conversationId());
        assertEquals("any update?", captor.getValue().body());
    }

    @Test
    void reply_serviceFailsInternally_returnsGenericFailure() {
        doThrow(new RuntimeException("conversation closed")).when(messaging).sendMessage(anyString(), any());

        AdminInboxPresenter.ActionOutcome.Failure fail =
            assertInstanceOf(AdminInboxPresenter.ActionOutcome.Failure.class, presenter.reply(TOKEN, "c1", "x"));
        assertEquals(ServiceErrors.GENERIC_FAILURE_MESSAGE, fail.reason());
    }

    @Test
    void reply_invalidToken_returnsNotAuthenticated() {
        doThrow(new InvalidTokenException("bad")).when(messaging).sendMessage(anyString(), any());

        assertInstanceOf(AdminInboxPresenter.ActionOutcome.NotAuthenticated.class,
            presenter.reply(TOKEN, "c1", "x"));
    }

    // -- close ----------------------------------------------------------------

    @Test
    void close_nullToken_returnsNotAuthenticated_withoutCallingService() {
        AdminInboxPresenter.ActionOutcome outcome = presenter.close(null, "c1");

        assertInstanceOf(AdminInboxPresenter.ActionOutcome.NotAuthenticated.class, outcome);
        verify(messaging, never()).closeConversation(anyString(), anyString());
    }

    @Test
    void close_happyPath_closesConversation() {
        AdminInboxPresenter.ActionOutcome outcome = presenter.close(TOKEN, "c1");

        assertInstanceOf(AdminInboxPresenter.ActionOutcome.Success.class, outcome);
        verify(messaging).closeConversation(TOKEN, "c1");
    }

    @Test
    void close_serviceFailsInternally_returnsGenericFailure() {
        doThrow(new RuntimeException("already closed")).when(messaging).closeConversation(anyString(), anyString());

        AdminInboxPresenter.ActionOutcome.Failure fail =
            assertInstanceOf(AdminInboxPresenter.ActionOutcome.Failure.class, presenter.close(TOKEN, "c1"));
        assertEquals(ServiceErrors.GENERIC_FAILURE_MESSAGE, fail.reason());
    }

    @Test
    void close_invalidToken_returnsNotAuthenticated() {
        doThrow(new InvalidTokenException("bad")).when(messaging).closeConversation(anyString(), anyString());

        assertInstanceOf(AdminInboxPresenter.ActionOutcome.NotAuthenticated.class,
            presenter.close(TOKEN, "c1"));
    }

    @Test
    void reply_conversationClosed_returnsFailureWithTheRulesMessage() {
        ConversationClosedException rule = new ConversationClosedException("c1");
        doThrow(rule).when(messaging).sendMessage(anyString(), any());

        AdminInboxPresenter.ActionOutcome.Failure fail =
            assertInstanceOf(AdminInboxPresenter.ActionOutcome.Failure.class, presenter.reply(TOKEN, "c1", "x"));
        assertEquals(rule.getMessage(), fail.reason());
    }

    // ---- Nothing ever marks a conversation read (#187) ----

    @Test
    void GivenAThread_WhenTheAdminOpensIt_ThenTheThreadIsMarkedRead() {
        AdminInboxPresenter.ActionOutcome outcome = presenter.markRead(TOKEN, "c1");

        assertInstanceOf(AdminInboxPresenter.ActionOutcome.Success.class, outcome);
        verify(messaging).markConversationRead(TOKEN, "c1");
    }

    @Test
    void GivenNoToken_WhenMarkRead_ThenNotAuthenticatedAndNothingIsMarked() {
        assertInstanceOf(AdminInboxPresenter.ActionOutcome.NotAuthenticated.class, presenter.markRead(null, "c1"));

        verify(messaging, never()).markConversationRead(anyString(), anyString());
    }

    @Test
    void GivenAnExpiredToken_WhenMarkRead_ThenNotAuthenticated() {
        doThrow(new InvalidTokenException("bad")).when(messaging).markConversationRead(anyString(), anyString());

        assertInstanceOf(AdminInboxPresenter.ActionOutcome.NotAuthenticated.class, presenter.markRead(TOKEN, "c1"));
    }

    @Test
    void GivenTheMarkFailsInternally_WhenMarkRead_ThenTheReasonIsTheGenericText() {
        doThrow(new RuntimeException("could not execute statement [update messages set is_read=?]"))
            .when(messaging).markConversationRead(anyString(), anyString());

        AdminInboxPresenter.ActionOutcome.Failure fail =
            assertInstanceOf(AdminInboxPresenter.ActionOutcome.Failure.class, presenter.markRead(TOKEN, "c1"));
        assertEquals(ServiceErrors.GENERIC_FAILURE_MESSAGE, fail.reason());
    }

    // ---- Mark read only on an explicit open (#187) ----
    //
    // A thread is marked read when the reader opens it, never when the view merely shows it, because a
    // mark reads it for the reader's whole side. The view keeps an OpenedThreads; the presenter decides.

    private static final LocalDateTime LAST_MESSAGE_AT = LocalDateTime.of(2027, 7, 15, 17, 0);

    private static ConversationDTO unreadThread(String id, int unread, LocalDateTime lastMessageAt) {
        return new ConversationDTO(id, "DIRECT", "OPEN", 1, "MEMBER", 7, "COMPANY", "Subject " + id,
                LAST_MESSAGE_AT, lastMessageAt, unread, List.of(), "alice", "Acme");
    }

    @Test
    void GivenAnUnreadThread_WhenTheAdminOpensIt_ThenItIsMarkedReadAndItsBadgeIsCleared() {
        OpenedThreads opened = new OpenedThreads();
        ConversationDTO thread = unreadThread("c1", 2, LAST_MESSAGE_AT);
        assertEquals(Optional.of("2"), presenter.badge(thread, opened));

        AdminInboxPresenter.ActionOutcome outcome = presenter.open(TOKEN, thread, opened);

        assertInstanceOf(AdminInboxPresenter.ActionOutcome.Success.class, outcome);
        verify(messaging).markConversationRead(TOKEN, "c1");
        assertEquals(Optional.empty(), presenter.badge(thread, opened));
    }

    @Test
    void GivenALoadedInbox_WhenItsRowsAreRenderedWithoutAnOpen_ThenNothingIsMarkedReadAndTheBadgeStays() {
        ConversationDTO thread = unreadThread("c1", 2, LAST_MESSAGE_AT);
        when(messaging.viewAdminInbox(TOKEN)).thenReturn(List.of(thread));
        List<ConversationDTO> listed = assertInstanceOf(AdminInboxPresenter.Outcome.Success.class,
                presenter.load(TOKEN)).conversations();

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

        AdminInboxPresenter.ActionOutcome.Failure fail = assertInstanceOf(AdminInboxPresenter.ActionOutcome.Failure.class,
                presenter.open(TOKEN, thread, opened));

        assertEquals(ServiceErrors.GENERIC_FAILURE_MESSAGE, fail.reason());
        assertEquals(Optional.of("2"), presenter.badge(thread, opened));
    }

    @Test
    void GivenNoToken_WhenOpened_ThenNotAuthenticatedNothingIsMarkedAndTheBadgeStays() {
        OpenedThreads opened = new OpenedThreads();
        ConversationDTO thread = unreadThread("c1", 2, LAST_MESSAGE_AT);

        assertInstanceOf(AdminInboxPresenter.ActionOutcome.NotAuthenticated.class, presenter.open(null, thread, opened));

        verify(messaging, never()).markConversationRead(anyString(), anyString());
        assertEquals(Optional.of("2"), presenter.badge(thread, opened));
    }

    @Test
    void GivenAnExpiredToken_WhenOpened_ThenNotAuthenticatedAndTheBadgeStays() {
        OpenedThreads opened = new OpenedThreads();
        ConversationDTO thread = unreadThread("c1", 2, LAST_MESSAGE_AT);
        doThrow(new InvalidTokenException("bad")).when(messaging).markConversationRead(anyString(), anyString());

        assertInstanceOf(AdminInboxPresenter.ActionOutcome.NotAuthenticated.class, presenter.open(TOKEN, thread, opened));

        assertEquals(Optional.of("2"), presenter.badge(thread, opened));
    }
}
