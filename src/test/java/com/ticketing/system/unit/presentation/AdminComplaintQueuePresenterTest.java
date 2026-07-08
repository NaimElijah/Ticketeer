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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.ticketing.system.Core.Application.dto.ComplaintFilterDTO;
import com.ticketing.system.Core.Application.dto.ConversationDTO;
import com.ticketing.system.Core.Application.dto.RespondToComplaintRequestDTO;
import com.ticketing.system.messaging.application.service.MessagingService;
import com.ticketing.system.shared.exception.InvalidTokenException;
import com.ticketing.system.Presentation.presenters.messaging.AdminComplaintQueuePresenter;

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
    void load_serviceThrows_returnsFailureWithMessage() {
        when(messaging.viewAllComplaints(eq(TOKEN), any()))
            .thenThrow(new RuntimeException("not an admin"));

        AdminComplaintQueuePresenter.Outcome outcome = presenter.load(TOKEN, "All");

        AdminComplaintQueuePresenter.Outcome.Failure fail =
            assertInstanceOf(AdminComplaintQueuePresenter.Outcome.Failure.class, outcome);
        assertEquals("not an admin", fail.reason());
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
    void respond_serviceThrows_returnsFailureWithMessage() {
        doThrow(new RuntimeException("already resolved"))
            .when(messaging).respondToComplaint(anyString(), any());

        AdminComplaintQueuePresenter.ActionOutcome outcome = presenter.respond(TOKEN, "c1", "late");

        AdminComplaintQueuePresenter.ActionOutcome.Failure fail =
            assertInstanceOf(AdminComplaintQueuePresenter.ActionOutcome.Failure.class, outcome);
        assertEquals("already resolved", fail.reason());
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
}
