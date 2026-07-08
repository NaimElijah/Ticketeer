package com.ticketing.system.unit.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
import com.ticketing.system.Core.Application.dto.MemberSearchResultDTO;
import com.ticketing.system.Core.Application.dto.MessageDTO;
import com.ticketing.system.Core.Application.dto.OutreachRequestDTO;
import com.ticketing.system.Core.Application.dto.OutreachResultDTO;
import com.ticketing.system.Core.Application.services.MemberQueryService;
import com.ticketing.system.messaging.application.service.MessagingService;
import com.ticketing.system.shared.exception.InvalidTokenException;
import com.ticketing.system.Presentation.presenters.messaging.AdminSendMessagesPresenter;

class AdminSendMessagesPresenterTest {

    private static final String TOKEN = "jwt-token";

    private MessagingService messaging;
    private MemberQueryService memberQuery;
    private AdminSendMessagesPresenter presenter;

    @BeforeEach
    void setUp() {
        messaging = mock(MessagingService.class);
        memberQuery = mock(MemberQueryService.class);
        presenter = new AdminSendMessagesPresenter(messaging, memberQuery);
    }

    /** A single DIRECT outreach conversation (admin → member) for the grouping tests. */
    private static ConversationDTO outreach(String id, int memberId, String subject, String body) {
        return new ConversationDTO(
            id, "DIRECT", "OPEN", 1, "ADMIN", memberId, "MEMBER",
            subject, LocalDateTime.now(), LocalDateTime.now(), 0,
            List.of(new MessageDTO("m-" + id, 1, "ADMIN", body, LocalDateTime.now(), false)),
            "TicketHub Support", "member" + memberId);
    }

    // -- send -----------------------------------------------------------------

    @Test
    void send_nullToken_returnsNotAuthenticated_withoutCallingService() {
        AdminSendMessagesPresenter.ActionOutcome outcome =
            presenter.send(null, "S", "B", List.of(1), false, false);

        assertInstanceOf(AdminSendMessagesPresenter.ActionOutcome.NotAuthenticated.class, outcome);
        verify(messaging, never()).sendOutreach(anyString(), any());
    }

    @Test
    void send_explicitRecipients_passesIds_andReturnsRecipientCount() {
        when(messaging.sendOutreach(eq(TOKEN), any())).thenReturn(new OutreachResultDTO(2, "conv-1"));

        AdminSendMessagesPresenter.ActionOutcome outcome =
            presenter.send(TOKEN, "Notice", "Body", List.of(7, 9), false, false);

        AdminSendMessagesPresenter.ActionOutcome.Success ok =
            assertInstanceOf(AdminSendMessagesPresenter.ActionOutcome.Success.class, outcome);
        assertEquals(2, ok.recipientCount());

        ArgumentCaptor<OutreachRequestDTO> captor = ArgumentCaptor.forClass(OutreachRequestDTO.class);
        verify(messaging).sendOutreach(eq(TOKEN), captor.capture());
        assertEquals("Notice", captor.getValue().subject());
        assertEquals(List.of(7, 9), captor.getValue().recipientMemberIds());
        assertTrue(!captor.getValue().allMembers() && !captor.getValue().allProducers());
    }

    @Test
    void send_allMembers_passesFlagThrough() {
        when(messaging.sendOutreach(eq(TOKEN), any())).thenReturn(new OutreachResultDTO(42, "conv-1"));

        presenter.send(TOKEN, "Notice", "Body", List.of(), true, false);

        ArgumentCaptor<OutreachRequestDTO> captor = ArgumentCaptor.forClass(OutreachRequestDTO.class);
        verify(messaging).sendOutreach(eq(TOKEN), captor.capture());
        assertTrue(captor.getValue().allMembers());
    }

    @Test
    void send_serviceThrows_returnsFailureWithMessage() {
        doThrow(new RuntimeException("no recipients")).when(messaging).sendOutreach(anyString(), any());

        AdminSendMessagesPresenter.ActionOutcome outcome =
            presenter.send(TOKEN, "S", "B", List.of(7), false, false);

        AdminSendMessagesPresenter.ActionOutcome.Failure fail =
            assertInstanceOf(AdminSendMessagesPresenter.ActionOutcome.Failure.class, outcome);
        assertEquals("no recipients", fail.reason());
    }

    @Test
    void send_invalidToken_returnsNotAuthenticated() {
        doThrow(new InvalidTokenException("bad")).when(messaging).sendOutreach(anyString(), any());

        AdminSendMessagesPresenter.ActionOutcome outcome =
            presenter.send(TOKEN, "S", "B", List.of(7), false, false);

        assertInstanceOf(AdminSendMessagesPresenter.ActionOutcome.NotAuthenticated.class, outcome);
    }

    // -- searchUsers ----------------------------------------------------------

    @Test
    void searchUsers_nullToken_returnsNotAuthenticated_withoutCallingService() {
        AdminSendMessagesPresenter.SearchOutcome outcome = presenter.searchUsers(null, "ali");

        assertInstanceOf(AdminSendMessagesPresenter.SearchOutcome.NotAuthenticated.class, outcome);
        verify(memberQuery, never()).searchByUsername(anyString());
    }

    @Test
    void searchUsers_returnsResults_fromMemberQueryService() {
        when(memberQuery.searchByUsername("ali"))
            .thenReturn(List.of(new MemberSearchResultDTO(7, "alice"), new MemberSearchResultDTO(8, "alistair")));

        AdminSendMessagesPresenter.SearchOutcome outcome = presenter.searchUsers(TOKEN, "ali");

        AdminSendMessagesPresenter.SearchOutcome.Success ok =
            assertInstanceOf(AdminSendMessagesPresenter.SearchOutcome.Success.class, outcome);
        assertEquals(2, ok.results().size());
    }

    // -- load (grouping) ------------------------------------------------------

    @Test
    void load_groupsFanOutBySubjectAndBody() {
        // Two recipients of the same logical send → one history row with recipientCount = 2.
        when(messaging.viewSentOutreach(TOKEN)).thenReturn(List.of(
            outreach("c1", 7, "Maintenance", "We are down tonight."),
            outreach("c2", 9, "Maintenance", "We are down tonight.")));

        AdminSendMessagesPresenter.Outcome outcome = presenter.load(TOKEN);

        AdminSendMessagesPresenter.Outcome.Success ok =
            assertInstanceOf(AdminSendMessagesPresenter.Outcome.Success.class, outcome);
        assertEquals(1, ok.history().size());
        assertEquals(2, ok.history().get(0).recipientCount());
        assertEquals("Maintenance", ok.history().get(0).subject());
    }

    @Test
    void load_nullToken_returnsNotAuthenticated_withoutCallingService() {
        AdminSendMessagesPresenter.Outcome outcome = presenter.load(null);

        assertInstanceOf(AdminSendMessagesPresenter.Outcome.NotAuthenticated.class, outcome);
        verify(messaging, never()).viewSentOutreach(anyString());
    }
}
