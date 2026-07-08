package com.ticketing.system.unit.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
import com.ticketing.system.Core.Application.dto.SubmitComplaintRequestDTO;
import com.ticketing.system.messaging.application.service.MessagingService;
import com.ticketing.system.shared.exception.InvalidTokenException;
import com.ticketing.system.Presentation.presenters.messaging.SubmitComplaintPresenter;

class SubmitComplaintPresenterTest {

    private static final String TOKEN = "jwt-token";

    private MessagingService service;
    private SubmitComplaintPresenter presenter;

    @BeforeEach
    void setUp() {
        service = mock(MessagingService.class);
        presenter = new SubmitComplaintPresenter(service);
    }

    private static ConversationDTO conversation(String id) {
        return new ConversationDTO(
            id, "COMPLAINT", "OPEN",
            1, "MEMBER", 0, "ADMIN_GROUP",
            "Refund delay", LocalDateTime.now(), LocalDateTime.now(),
            0, List.of(), "alice", "TicketHub Support");
    }

    @Test
    void submit_nullToken_returnsNotAuthenticated_withoutCallingService() {
        SubmitComplaintPresenter.Outcome outcome =
            presenter.submit(null, "Subject", "Body", "Payment or refund");

        assertInstanceOf(SubmitComplaintPresenter.Outcome.NotAuthenticated.class, outcome);
        verify(service, never()).submitComplaint(anyString(), any());
    }

    @Test
    void submit_happyPath_returnsSuccessWithConversationId_andForwardsFormFields() {
        when(service.submitComplaint(eq(TOKEN), any())).thenReturn(conversation("conv-42"));

        SubmitComplaintPresenter.Outcome outcome =
            presenter.submit(TOKEN, "Subject", "Body", "Payment or refund");

        SubmitComplaintPresenter.Outcome.Success ok =
            assertInstanceOf(SubmitComplaintPresenter.Outcome.Success.class, outcome);
        assertEquals("conv-42", ok.conversationId());

        ArgumentCaptor<SubmitComplaintRequestDTO> captor =
            ArgumentCaptor.forClass(SubmitComplaintRequestDTO.class);
        verify(service).submitComplaint(eq(TOKEN), captor.capture());
        SubmitComplaintRequestDTO sent = captor.getValue();
        assertEquals("Subject", sent.subject());
        assertEquals("Body", sent.body());
        assertEquals("Payment or refund", sent.relatedEntityRef());
    }

    @Test
    void submit_serviceThrows_returnsFailureWithMessage() {
        when(service.submitComplaint(eq(TOKEN), any()))
            .thenThrow(new RuntimeException("backend down"));

        SubmitComplaintPresenter.Outcome outcome =
            presenter.submit(TOKEN, "Subject", "Body", null);

        SubmitComplaintPresenter.Outcome.Failure fail =
            assertInstanceOf(SubmitComplaintPresenter.Outcome.Failure.class, outcome);
        assertEquals("backend down", fail.reason());
    }

    @Test
    void submit_invalidToken_returnsNotAuthenticated() {
        when(service.submitComplaint(eq(TOKEN), any()))
            .thenThrow(new InvalidTokenException("bad"));

        SubmitComplaintPresenter.Outcome outcome =
            presenter.submit(TOKEN, "Subject", "Body", null);

        assertInstanceOf(SubmitComplaintPresenter.Outcome.NotAuthenticated.class, outcome);
    }
}
