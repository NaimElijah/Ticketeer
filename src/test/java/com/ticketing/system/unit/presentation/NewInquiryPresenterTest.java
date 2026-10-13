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

import com.ticketing.system.shared.dto.CompanySummaryDTO;
import com.ticketing.system.shared.dto.ConversationDTO;
import com.ticketing.system.shared.dto.StartConversationRequestDTO;
import com.ticketing.system.catalog.application.service.CatalogService;
import com.ticketing.system.messaging.application.service.MessagingService;
import com.ticketing.system.shared.exception.InvalidTokenException;
import com.ticketing.system.ui.presenters.messaging.NewInquiryPresenter;

class NewInquiryPresenterTest {

    private static final String TOKEN = "jwt-token";

    private MessagingService messaging;
    private CatalogService catalog;
    private NewInquiryPresenter presenter;

    @BeforeEach
    void setUp() {
        messaging = mock(MessagingService.class);
        catalog = mock(CatalogService.class);
        presenter = new NewInquiryPresenter(messaging, catalog);
    }

    // -- searchCompanies ------------------------------------------------------

    @Test
    void searchCompanies_returnsMatches() {
        when(catalog.searchCompaniesByName("ac"))
            .thenReturn(List.of(new CompanySummaryDTO(1, "Acme", null)));

        NewInquiryPresenter.SearchOutcome outcome = presenter.searchCompanies("ac");

        NewInquiryPresenter.SearchOutcome.Success ok =
            assertInstanceOf(NewInquiryPresenter.SearchOutcome.Success.class, outcome);
        assertEquals(1, ok.companies().size());
        assertEquals("Acme", ok.companies().get(0).name());
    }

    @Test
    void searchCompanies_serviceThrows_returnsFailure() {
        when(catalog.searchCompaniesByName(anyString())).thenThrow(new RuntimeException("catalog down"));

        NewInquiryPresenter.SearchOutcome outcome = presenter.searchCompanies("ac");

        NewInquiryPresenter.SearchOutcome.Failure fail =
            assertInstanceOf(NewInquiryPresenter.SearchOutcome.Failure.class, outcome);
        assertEquals("catalog down", fail.reason());
    }

    // -- submit ---------------------------------------------------------------

    @Test
    void submit_nullToken_returnsNotAuthenticated_withoutCallingService() {
        NewInquiryPresenter.ActionOutcome outcome = presenter.submit(null, 5, "S", "B");

        assertInstanceOf(NewInquiryPresenter.ActionOutcome.NotAuthenticated.class, outcome);
        verify(messaging, never()).startConversation(anyString(), any());
    }

    @Test
    void submit_happyPath_returnsConversationId_andTargetsCompany() {
        ConversationDTO conv = new ConversationDTO(
            "conv-1", "INQUIRY", "OPEN", 1, "MEMBER", 5, "COMPANY",
            "Parking?", LocalDateTime.now(), LocalDateTime.now(), 0, List.of(),
            "alice", "Acme");
        when(messaging.startConversation(eq(TOKEN), any())).thenReturn(conv);

        NewInquiryPresenter.ActionOutcome outcome = presenter.submit(TOKEN, 5, "Parking?", "Is there parking?");

        NewInquiryPresenter.ActionOutcome.Success ok =
            assertInstanceOf(NewInquiryPresenter.ActionOutcome.Success.class, outcome);
        assertEquals("conv-1", ok.conversationId());

        ArgumentCaptor<StartConversationRequestDTO> captor =
            ArgumentCaptor.forClass(StartConversationRequestDTO.class);
        verify(messaging).startConversation(eq(TOKEN), captor.capture());
        assertEquals(5, captor.getValue().counterpartyId());
        assertEquals("Parking?", captor.getValue().subject());
    }

    @Test
    void submit_serviceThrows_returnsFailureWithMessage() {
        doThrow(new RuntimeException("company not found"))
            .when(messaging).startConversation(anyString(), any());

        NewInquiryPresenter.ActionOutcome outcome = presenter.submit(TOKEN, 5, "S", "B");

        NewInquiryPresenter.ActionOutcome.Failure fail =
            assertInstanceOf(NewInquiryPresenter.ActionOutcome.Failure.class, outcome);
        assertEquals("company not found", fail.reason());
    }

    @Test
    void submit_invalidToken_returnsNotAuthenticated() {
        doThrow(new InvalidTokenException("bad"))
            .when(messaging).startConversation(anyString(), any());

        NewInquiryPresenter.ActionOutcome outcome = presenter.submit(TOKEN, 5, "S", "B");

        assertInstanceOf(NewInquiryPresenter.ActionOutcome.NotAuthenticated.class, outcome);
    }
}
