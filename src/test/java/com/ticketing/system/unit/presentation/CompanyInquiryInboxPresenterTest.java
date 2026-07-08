package com.ticketing.system.unit.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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
import com.ticketing.system.Core.Application.dto.MyCompanyDTO;
import com.ticketing.system.Core.Application.dto.SendMessageRequestDTO;
import com.ticketing.system.organization.application.service.CompanyManagementService;
import com.ticketing.system.messaging.application.service.MessagingService;
import com.ticketing.system.shared.exception.InvalidTokenException;
import com.ticketing.system.Presentation.presenters.messaging.CompanyInquiryInboxPresenter;

class CompanyInquiryInboxPresenterTest {

    private static final String TOKEN = "jwt-token";

    private MessagingService messaging;
    private CompanyManagementService companyManagement;
    private CompanyInquiryInboxPresenter presenter;

    @BeforeEach
    void setUp() {
        messaging = mock(MessagingService.class);
        companyManagement = mock(CompanyManagementService.class);
        presenter = new CompanyInquiryInboxPresenter(messaging, companyManagement);
    }

    private static MyCompanyDTO company(int id, String name) {
        return new MyCompanyDTO(id, name, "Founder");
    }

    private static ConversationDTO conversation(String id, String type) {
        return new ConversationDTO(
            id, type, "OPEN", 1, "MEMBER", 7, "COMPANY",
            "Subject " + id, LocalDateTime.now(), LocalDateTime.now(), 0, List.of(),
            "alice", "Acme");
    }

    // -- loadFor --------------------------------------------------------------

    @Test
    void loadFor_nullToken_returnsNotAuthenticated_withoutCallingServices() {
        CompanyInquiryInboxPresenter.Outcome outcome = presenter.loadFor(null, 7);

        assertInstanceOf(CompanyInquiryInboxPresenter.Outcome.NotAuthenticated.class, outcome);
        verify(companyManagement, never()).findMyCompanies(anyString());
        verify(messaging, never()).viewCompanyInbox(anyString(), anyInt());
    }

    @Test
    void loadFor_noCompanies_returnsNoCompany_withoutQueryingInbox() {
        when(companyManagement.findMyCompanies(TOKEN)).thenReturn(List.of());

        CompanyInquiryInboxPresenter.Outcome outcome = presenter.loadFor(TOKEN, null);

        assertInstanceOf(CompanyInquiryInboxPresenter.Outcome.NoCompany.class, outcome);
        verify(messaging, never()).viewCompanyInbox(anyString(), anyInt());
    }

    @Test
    void loadFor_selectsRequestedCompany_andKeepsOnlyInquiries() {
        MyCompanyDTO other = company(9, "Other");
        MyCompanyDTO acme = company(7, "Acme");
        when(companyManagement.findMyCompanies(TOKEN)).thenReturn(List.of(other, acme));
        ConversationDTO inquiry = conversation("c1", "INQUIRY");
        ConversationDTO direct = conversation("c2", "DIRECT");
        when(messaging.viewCompanyInbox(TOKEN, 7)).thenReturn(List.of(inquiry, direct));

        CompanyInquiryInboxPresenter.Outcome outcome = presenter.loadFor(TOKEN, 7);

        CompanyInquiryInboxPresenter.Outcome.Success ok =
            assertInstanceOf(CompanyInquiryInboxPresenter.Outcome.Success.class, outcome);
        assertEquals(7, ok.selected().companyId());
        // Only member inquiries are shown (the inbox keeps INQUIRY threads).
        assertEquals(List.of(inquiry), ok.conversations());
        verify(messaging).viewCompanyInbox(TOKEN, 7);
    }

    @Test
    void loadFor_defaultsToFirstCompany_whenCompanyIdNull() {
        MyCompanyDTO first = company(7, "Acme");
        MyCompanyDTO second = company(9, "Other");
        when(companyManagement.findMyCompanies(TOKEN)).thenReturn(List.of(first, second));
        when(messaging.viewCompanyInbox(TOKEN, 7)).thenReturn(List.of());

        CompanyInquiryInboxPresenter.Outcome outcome = presenter.loadFor(TOKEN, null);

        CompanyInquiryInboxPresenter.Outcome.Success ok =
            assertInstanceOf(CompanyInquiryInboxPresenter.Outcome.Success.class, outcome);
        assertEquals(7, ok.selected().companyId());
        verify(messaging).viewCompanyInbox(TOKEN, 7);
    }

    @Test
    void loadFor_invalidToken_returnsNotAuthenticated() {
        when(companyManagement.findMyCompanies(TOKEN)).thenThrow(new InvalidTokenException("bad"));

        CompanyInquiryInboxPresenter.Outcome outcome = presenter.loadFor(TOKEN, 7);

        assertInstanceOf(CompanyInquiryInboxPresenter.Outcome.NotAuthenticated.class, outcome);
    }

    @Test
    void loadFor_serviceThrows_returnsFailureWithMessage() {
        when(companyManagement.findMyCompanies(TOKEN)).thenReturn(List.of(company(7, "Acme")));
        when(messaging.viewCompanyInbox(TOKEN, 7)).thenThrow(new RuntimeException("inbox down"));

        CompanyInquiryInboxPresenter.Outcome outcome = presenter.loadFor(TOKEN, 7);

        CompanyInquiryInboxPresenter.Outcome.Failure fail =
            assertInstanceOf(CompanyInquiryInboxPresenter.Outcome.Failure.class, outcome);
        assertEquals("inbox down", fail.reason());
    }

    // -- reply ----------------------------------------------------------------

    @Test
    void reply_nullToken_returnsNotAuthenticated_withoutCallingService() {
        CompanyInquiryInboxPresenter.ActionOutcome outcome = presenter.reply(null, "c1", "hi");

        assertInstanceOf(CompanyInquiryInboxPresenter.ActionOutcome.NotAuthenticated.class, outcome);
        verify(messaging, never()).sendMessage(anyString(), any());
    }

    @Test
    void reply_happyPath_returnsSuccess_andSendsConversationIdAndBody() {
        CompanyInquiryInboxPresenter.ActionOutcome outcome =
            presenter.reply(TOKEN, "c1", "on it — accessible seats held");

        assertInstanceOf(CompanyInquiryInboxPresenter.ActionOutcome.Success.class, outcome);
        ArgumentCaptor<SendMessageRequestDTO> captor =
            ArgumentCaptor.forClass(SendMessageRequestDTO.class);
        verify(messaging).sendMessage(eq(TOKEN), captor.capture());
        assertEquals("c1", captor.getValue().conversationId());
        assertEquals("on it — accessible seats held", captor.getValue().body());
    }

    @Test
    void reply_serviceThrows_returnsFailureWithMessage() {
        doThrow(new RuntimeException("conversation closed"))
            .when(messaging).sendMessage(anyString(), any());

        CompanyInquiryInboxPresenter.ActionOutcome outcome = presenter.reply(TOKEN, "c1", "late reply");

        CompanyInquiryInboxPresenter.ActionOutcome.Failure fail =
            assertInstanceOf(CompanyInquiryInboxPresenter.ActionOutcome.Failure.class, outcome);
        assertEquals("conversation closed", fail.reason());
    }

    @Test
    void reply_invalidToken_returnsNotAuthenticated() {
        doThrow(new InvalidTokenException("bad"))
            .when(messaging).sendMessage(anyString(), any());

        CompanyInquiryInboxPresenter.ActionOutcome outcome = presenter.reply(TOKEN, "c1", "hi");

        assertInstanceOf(CompanyInquiryInboxPresenter.ActionOutcome.NotAuthenticated.class, outcome);
    }

    // -- close ----------------------------------------------------------------

    @Test
    void close_nullToken_returnsNotAuthenticated_withoutCallingService() {
        CompanyInquiryInboxPresenter.ActionOutcome outcome = presenter.close(null, "c1");

        assertInstanceOf(CompanyInquiryInboxPresenter.ActionOutcome.NotAuthenticated.class, outcome);
        verify(messaging, never()).closeConversation(anyString(), anyString());
    }

    @Test
    void close_happyPath_returnsSuccess_andClosesConversation() {
        CompanyInquiryInboxPresenter.ActionOutcome outcome = presenter.close(TOKEN, "c1");

        assertInstanceOf(CompanyInquiryInboxPresenter.ActionOutcome.Success.class, outcome);
        verify(messaging).closeConversation(TOKEN, "c1");
    }

    @Test
    void close_serviceThrows_returnsFailureWithMessage() {
        doThrow(new RuntimeException("already closed"))
            .when(messaging).closeConversation(anyString(), anyString());

        CompanyInquiryInboxPresenter.ActionOutcome outcome = presenter.close(TOKEN, "c1");

        CompanyInquiryInboxPresenter.ActionOutcome.Failure fail =
            assertInstanceOf(CompanyInquiryInboxPresenter.ActionOutcome.Failure.class, outcome);
        assertEquals("already closed", fail.reason());
    }

    @Test
    void close_invalidToken_returnsNotAuthenticated() {
        doThrow(new InvalidTokenException("bad"))
            .when(messaging).closeConversation(anyString(), anyString());

        CompanyInquiryInboxPresenter.ActionOutcome outcome = presenter.close(TOKEN, "c1");

        assertInstanceOf(CompanyInquiryInboxPresenter.ActionOutcome.NotAuthenticated.class, outcome);
    }

    // -- loadOne --------------------------------------------------------------

    @Test
    void loadOne_nullToken_returnsNotAuthenticated_withoutCallingService() {
        CompanyInquiryInboxPresenter.SingleOutcome outcome = presenter.loadOne(null, "c1");

        assertInstanceOf(CompanyInquiryInboxPresenter.SingleOutcome.NotAuthenticated.class, outcome);
        verify(messaging, never()).viewConversation(anyString(), anyString());
    }

    @Test
    void loadOne_success_returnsConversation() {
        ConversationDTO inquiry = conversation("c1", "INQUIRY");
        when(messaging.viewConversation(TOKEN, "c1")).thenReturn(inquiry);

        CompanyInquiryInboxPresenter.SingleOutcome outcome = presenter.loadOne(TOKEN, "c1");

        CompanyInquiryInboxPresenter.SingleOutcome.Success ok =
            assertInstanceOf(CompanyInquiryInboxPresenter.SingleOutcome.Success.class, outcome);
        assertEquals(inquiry, ok.conversation());
    }
}
