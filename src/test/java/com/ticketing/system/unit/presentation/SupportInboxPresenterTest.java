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

import com.ticketing.system.Core.Application.dto.ConversationDTO;
import com.ticketing.system.Core.Application.dto.SendMessageRequestDTO;
import com.ticketing.system.messaging.application.service.MessagingService;
import com.ticketing.system.shared.exception.InvalidTokenException;
import com.ticketing.system.ui.presenters.messaging.SupportInboxPresenter;

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
    void load_serviceThrows_returnsFailureWithMessage() {
        when(service.viewMyConversations(TOKEN)).thenThrow(new RuntimeException("backend down"));

        SupportInboxPresenter.Outcome outcome = presenter.load(TOKEN);

        SupportInboxPresenter.Outcome.Failure fail =
            assertInstanceOf(SupportInboxPresenter.Outcome.Failure.class, outcome);
        assertEquals("backend down", fail.reason());
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
    void reply_serviceThrows_returnsFailureWithMessage() {
        doThrow(new RuntimeException("conversation closed"))
            .when(service).sendMessage(anyString(), any());

        SupportInboxPresenter.ActionOutcome outcome = presenter.reply(TOKEN, "c1", "late reply");

        SupportInboxPresenter.ActionOutcome.Failure fail =
            assertInstanceOf(SupportInboxPresenter.ActionOutcome.Failure.class, outcome);
        assertEquals("conversation closed", fail.reason());
    }

    @Test
    void reply_invalidToken_returnsNotAuthenticated() {
        doThrow(new InvalidTokenException("bad"))
            .when(service).sendMessage(anyString(), any());

        SupportInboxPresenter.ActionOutcome outcome = presenter.reply(TOKEN, "c1", "hi");

        assertInstanceOf(SupportInboxPresenter.ActionOutcome.NotAuthenticated.class, outcome);
    }
}
