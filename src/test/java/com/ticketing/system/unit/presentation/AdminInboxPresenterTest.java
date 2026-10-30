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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.ticketing.system.shared.dto.ConversationDTO;
import com.ticketing.system.shared.dto.SendMessageRequestDTO;
import com.ticketing.system.messaging.application.service.MessagingService;
import com.ticketing.system.shared.exception.InvalidTokenException;
import com.ticketing.system.ui.presenters.messaging.AdminInboxPresenter;
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
}
