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

import jakarta.persistence.PersistenceException;

import com.ticketing.system.shared.dto.ConversationDTO;
import com.ticketing.system.shared.dto.SendMessageRequestDTO;
import com.ticketing.system.messaging.application.service.MessagingService;
import com.ticketing.system.shared.exception.InvalidTokenException;
import com.ticketing.system.ui.presenters.messaging.SupportInboxPresenter;
import com.ticketing.system.ui.presenters.messaging.OpenedThreads;
import com.ticketing.system.ui.support.ServiceErrors;
import com.ticketing.system.shared.exception.ConversationClosedException;

class SupportInboxPresenterTest {

    private static final String TOKEN = "jwt-token";

    private MessagingService service;
    private SupportInboxPresenter presenter;

    @BeforeEach
    void setUp() {
        service = mock(MessagingService.class);
        presenter = new SupportInboxPresenter(service);
    }

    private static ConversationDTO conversation(String id, String type, String initiatorType) {
        return new ConversationDTO(
            id, type, "OPEN",
            initiatorType.equals("MEMBER") ? 1 : 0, initiatorType,
            initiatorType.equals("MEMBER") ? 0 : 1,
            initiatorType.equals("MEMBER") ? "ADMIN_GROUP" : "MEMBER",
            "Subject " + id, LocalDateTime.now(), LocalDateTime.now(), 0, List.of(),
            "alice", "TicketHub Support");
    }

    // -- load -----------------------------------------------------------------

    @Test
    void load_nullToken_returnsNotAuthenticated_withoutCallingService() {
        SupportInboxPresenter.Outcome outcome = presenter.load(null);

        assertInstanceOf(SupportInboxPresenter.Outcome.NotAuthenticated.class, outcome);
        verify(service, never()).viewMyConversations(anyString());
    }

    @Test
    void load_returnsAllConversationsFromService() {
        ConversationDTO complaint = conversation("c1", "COMPLAINT", "MEMBER");
        ConversationDTO inquiry = conversation("c2", "INQUIRY", "MEMBER");
        ConversationDTO outreach = conversation("c3", "DIRECT", "ADMIN");   // admin → member, member is counterparty
        when(service.viewMyConversations(TOKEN))
            .thenReturn(List.of(complaint, inquiry, outreach));

        SupportInboxPresenter.Outcome outcome = presenter.load(TOKEN);

        SupportInboxPresenter.Outcome.Success ok =
            assertInstanceOf(SupportInboxPresenter.Outcome.Success.class, outcome);
        // The service (findMemberInbox) already scopes the inbox; the presenter does not filter,
        // so admin → member outreach (DIRECT) surfaces alongside the member's own threads.
        assertEquals(List.of(complaint, inquiry, outreach), ok.conversations());
    }

    @Test
    void load_serviceFailsInternally_returnsGenericFailure() {
        when(service.viewMyConversations(TOKEN)).thenThrow(new RuntimeException("backend down"));

        SupportInboxPresenter.Outcome outcome = presenter.load(TOKEN);

        SupportInboxPresenter.Outcome.Failure fail =
            assertInstanceOf(SupportInboxPresenter.Outcome.Failure.class, outcome);
        assertEquals(ServiceErrors.GENERIC_FAILURE_MESSAGE, fail.reason());
    }

    @Test
    void GivenTheInboxFailsInternally_WhenLoad_ThenTheReasonIsTheGenericText() {
        // A non-domain failure is an internal error: its text must not reach the member (CWE-209).
        when(service.viewMyConversations(TOKEN))
            .thenThrow(new PersistenceException("could not extract ResultSet [select c.conversation_id from conversations c]"));

        SupportInboxPresenter.Outcome.Failure fail =
            assertInstanceOf(SupportInboxPresenter.Outcome.Failure.class, presenter.load(TOKEN));

        assertEquals(ServiceErrors.GENERIC_FAILURE_MESSAGE, fail.reason());
    }

    @Test
    void GivenTheReplyFailsInternally_WhenReply_ThenTheReasonIsTheGenericText() {
        doThrow(new RuntimeException("could not execute statement [insert into messages (body)] constraint fk_messages_conversation_id"))
            .when(service).sendMessage(anyString(), any());

        SupportInboxPresenter.ActionOutcome.Failure fail =
            assertInstanceOf(SupportInboxPresenter.ActionOutcome.Failure.class,
                presenter.reply(TOKEN, "c1", "any update?"));

        assertEquals(ServiceErrors.GENERIC_FAILURE_MESSAGE, fail.reason());
    }

    @Test
    void load_invalidToken_returnsNotAuthenticated() {
        when(service.viewMyConversations(TOKEN)).thenThrow(new InvalidTokenException("bad"));

        SupportInboxPresenter.Outcome outcome = presenter.load(TOKEN);

        assertInstanceOf(SupportInboxPresenter.Outcome.NotAuthenticated.class, outcome);
    }

    // -- reply ----------------------------------------------------------------

    @Test
    void reply_nullToken_returnsNotAuthenticated_withoutCallingService() {
        SupportInboxPresenter.ActionOutcome outcome = presenter.reply(null, "c1", "hi");

        assertInstanceOf(SupportInboxPresenter.ActionOutcome.NotAuthenticated.class, outcome);
        verify(service, never()).sendMessage(anyString(), any());
    }

    @Test
    void reply_happyPath_returnsSuccess_andSendsConversationIdAndBody() {
        SupportInboxPresenter.ActionOutcome outcome =
            presenter.reply(TOKEN, "c1", "any update?");

        assertInstanceOf(SupportInboxPresenter.ActionOutcome.Success.class, outcome);
        ArgumentCaptor<SendMessageRequestDTO> captor =
            ArgumentCaptor.forClass(SendMessageRequestDTO.class);
        verify(service).sendMessage(eq(TOKEN), captor.capture());
        assertEquals("c1", captor.getValue().conversationId());
        assertEquals("any update?", captor.getValue().body());
    }

    @Test
    void reply_serviceFailsInternally_returnsGenericFailure() {
        doThrow(new RuntimeException("conversation closed"))
            .when(service).sendMessage(anyString(), any());

        SupportInboxPresenter.ActionOutcome outcome = presenter.reply(TOKEN, "c1", "late reply");

        SupportInboxPresenter.ActionOutcome.Failure fail =
            assertInstanceOf(SupportInboxPresenter.ActionOutcome.Failure.class, outcome);
        assertEquals(ServiceErrors.GENERIC_FAILURE_MESSAGE, fail.reason());
    }

    @Test
    void reply_invalidToken_returnsNotAuthenticated() {
        doThrow(new InvalidTokenException("bad"))
            .when(service).sendMessage(anyString(), any());

        SupportInboxPresenter.ActionOutcome outcome = presenter.reply(TOKEN, "c1", "hi");

        assertInstanceOf(SupportInboxPresenter.ActionOutcome.NotAuthenticated.class, outcome);
    }

    @Test
    void reply_conversationClosed_returnsFailureWithTheRulesMessage() {
        ConversationClosedException rule = new ConversationClosedException("c1");
        doThrow(rule).when(service).sendMessage(anyString(), any());

        SupportInboxPresenter.ActionOutcome.Failure fail = assertInstanceOf(
            SupportInboxPresenter.ActionOutcome.Failure.class, presenter.reply(TOKEN, "c1", "late reply"));
        assertEquals(rule.getMessage(), fail.reason());
    }

    // ---- Mark read only on an explicit open (#187) ----
    //
    // A thread is marked read when the reader opens it, never when the view merely shows it, because a
    // mark reads it for the reader's whole side. The view keeps an OpenedThreads; the presenter decides.

    private static final LocalDateTime LAST_MESSAGE_AT = LocalDateTime.of(2027, 7, 15, 17, 0);

    private static ConversationDTO unreadThread(String id, int unread, LocalDateTime lastMessageAt) {
        return new ConversationDTO(id, "INQUIRY", "OPEN", 1, "MEMBER", 7, "COMPANY", "Subject " + id,
                LAST_MESSAGE_AT, lastMessageAt, unread, List.of(), "alice", "Acme");
    }

    @Test
    void GivenAnUnreadThread_WhenTheMemberOpensIt_ThenItIsMarkedReadAndItsBadgeIsCleared() {
        OpenedThreads opened = new OpenedThreads();
        ConversationDTO thread = unreadThread("c1", 2, LAST_MESSAGE_AT);
        assertEquals(Optional.of("2"), presenter.badge(thread, opened));

        SupportInboxPresenter.ActionOutcome outcome = presenter.open(TOKEN, thread, opened);

        assertInstanceOf(SupportInboxPresenter.ActionOutcome.Success.class, outcome);
        verify(service).markConversationRead(TOKEN, "c1");
        assertEquals(Optional.empty(), presenter.badge(thread, opened));
    }

    @Test
    void GivenALoadedInbox_WhenItsRowsAreRenderedWithoutAnOpen_ThenNothingIsMarkedReadAndTheBadgeStays() {
        ConversationDTO thread = unreadThread("c1", 2, LAST_MESSAGE_AT);
        when(service.viewMyConversations(TOKEN)).thenReturn(List.of(thread));
        List<ConversationDTO> listed = assertInstanceOf(SupportInboxPresenter.Outcome.Success.class,
                presenter.load(TOKEN)).conversations();

        List<Optional<String>> badges = listed.stream().map(c -> presenter.badge(c, new OpenedThreads())).toList();

        assertEquals(List.of(Optional.of("2")), badges);
        verify(service, never()).markConversationRead(anyString(), anyString());
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
            .when(service).markConversationRead(anyString(), anyString());

        SupportInboxPresenter.ActionOutcome.Failure fail = assertInstanceOf(SupportInboxPresenter.ActionOutcome.Failure.class,
                presenter.open(TOKEN, thread, opened));

        assertEquals(ServiceErrors.GENERIC_FAILURE_MESSAGE, fail.reason());
        assertEquals(Optional.of("2"), presenter.badge(thread, opened));
    }

    @Test
    void GivenNoToken_WhenOpened_ThenNotAuthenticatedNothingIsMarkedAndTheBadgeStays() {
        OpenedThreads opened = new OpenedThreads();
        ConversationDTO thread = unreadThread("c1", 2, LAST_MESSAGE_AT);

        assertInstanceOf(SupportInboxPresenter.ActionOutcome.NotAuthenticated.class, presenter.open(null, thread, opened));

        verify(service, never()).markConversationRead(anyString(), anyString());
        assertEquals(Optional.of("2"), presenter.badge(thread, opened));
    }

    @Test
    void GivenAnExpiredToken_WhenOpened_ThenNotAuthenticatedAndTheBadgeStays() {
        OpenedThreads opened = new OpenedThreads();
        ConversationDTO thread = unreadThread("c1", 2, LAST_MESSAGE_AT);
        doThrow(new InvalidTokenException("bad")).when(service).markConversationRead(anyString(), anyString());

        assertInstanceOf(SupportInboxPresenter.ActionOutcome.NotAuthenticated.class, presenter.open(TOKEN, thread, opened));

        assertEquals(Optional.of("2"), presenter.badge(thread, opened));
    }
}
