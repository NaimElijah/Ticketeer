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
import com.ticketing.system.ui.support.ServiceErrors;
import com.ticketing.system.shared.exception.CompanyNotFoundException;
import com.ticketing.system.shared.exception.SessionExpiredException;

class NewInquiryPresenterTest {

    private static final LocalDateTime STARTED_AT = LocalDateTime.of(2027, 7, 15, 17, 0);

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
    void GivenAMatchingCompany_WhenSearchCompanies_ThenReturnsMatches() {
        when(catalog.searchCompaniesByName("ac"))
            .thenReturn(List.of(new CompanySummaryDTO(1, "Acme", null)));

        NewInquiryPresenter.SearchOutcome outcome = presenter.searchCompanies("ac");

        NewInquiryPresenter.SearchOutcome.Success ok =
            assertInstanceOf(NewInquiryPresenter.SearchOutcome.Success.class, outcome);
        assertEquals(1, ok.companies().size());
        assertEquals("Acme", ok.companies().get(0).name());
    }

    @Test
    void GivenTheServiceThrows_WhenSearchCompanies_ThenReturnsGenericFailure() {
        when(catalog.searchCompaniesByName(anyString())).thenThrow(new RuntimeException("catalog down"));

        NewInquiryPresenter.SearchOutcome outcome = presenter.searchCompanies("ac");

        NewInquiryPresenter.SearchOutcome.Failure fail =
            assertInstanceOf(NewInquiryPresenter.SearchOutcome.Failure.class, outcome);
        assertEquals(ServiceErrors.GENERIC_FAILURE_MESSAGE, fail.reason());
    }

    // -- submit ---------------------------------------------------------------

    @Test
    void GivenANullToken_WhenSubmit_ThenReturnsNotAuthenticatedWithoutCallingService() {
        NewInquiryPresenter.ActionOutcome outcome = presenter.submit(null, 5, "S", "B");

        assertInstanceOf(NewInquiryPresenter.ActionOutcome.NotAuthenticated.class, outcome);
        verify(messaging, never()).startConversation(anyString(), any());
    }

    @Test
    void GivenTheServiceStartsAConversation_WhenSubmit_ThenReturnsConversationIdAndTargetsCompany() {
        ConversationDTO conv = new ConversationDTO(
            "conv-1", "INQUIRY", "OPEN", 1, "MEMBER", 5, "COMPANY",
            "Parking?", STARTED_AT, STARTED_AT, 0, List.of(),
            "alice", "Acme");
        when(messaging.startConversation(eq(TOKEN), any())).thenReturn(conv);

        NewInquiryPresenter.ActionOutcome outcome = presenter.submit(TOKEN, 5, "Parking?", "Is there parking?");

        NewInquiryPresenter.ActionOutcome.Success ok =
            assertInstanceOf(NewInquiryPresenter.ActionOutcome.Success.class, outcome);
        assertEquals("conv-1", ok.conversationId());

        ArgumentCaptor<StartConversationRequestDTO> captor =
            ArgumentCaptor.forClass(StartConversationRequestDTO.class);
        verify(messaging).startConversation(eq(TOKEN), captor.capture());
        assertEquals(new StartConversationRequestDTO(5, "Parking?", "Is there parking?"), captor.getValue(),
            "the inquiry goes to the company with the subject and body as typed");
    }

    @Test
    void GivenTheServiceFailsInternally_WhenSubmit_ThenReturnsGenericFailure() {
        doThrow(new RuntimeException("could not execute statement"))
            .when(messaging).startConversation(anyString(), any());

        NewInquiryPresenter.ActionOutcome outcome = presenter.submit(TOKEN, 5, "S", "B");

        NewInquiryPresenter.ActionOutcome.Failure fail =
            assertInstanceOf(NewInquiryPresenter.ActionOutcome.Failure.class, outcome);
        assertEquals(ServiceErrors.GENERIC_FAILURE_MESSAGE, fail.reason());
    }

    @Test
    void GivenAnInvalidToken_WhenSubmit_ThenReturnsNotAuthenticated() {
        doThrow(new InvalidTokenException("bad"))
            .when(messaging).startConversation(anyString(), any());

        NewInquiryPresenter.ActionOutcome outcome = presenter.submit(TOKEN, 5, "S", "B");

        assertInstanceOf(NewInquiryPresenter.ActionOutcome.NotAuthenticated.class, outcome);
    }

    @Test
    void GivenTheCompanyIsNotFound_WhenSubmit_ThenReturnsFailureWithTheRulesMessage() {
        CompanyNotFoundException rule = new CompanyNotFoundException();
        doThrow(rule).when(messaging).startConversation(anyString(), any());

        NewInquiryPresenter.ActionOutcome.Failure fail =
            assertInstanceOf(NewInquiryPresenter.ActionOutcome.Failure.class, presenter.submit(TOKEN, 5, "S", "B"));
        assertEquals(rule.getMessage(), fail.reason());
    }

    // ---- an expired session reads as a dead one, as a revoked token does (ADR-025, #189) ----

    @Test
    void GivenAnExpiredSession_WhenSubmit_ThenReturnsNotAuthenticated() {
        doThrow(new SessionExpiredException()).when(messaging).startConversation(anyString(), any());

        assertInstanceOf(NewInquiryPresenter.ActionOutcome.NotAuthenticated.class,
                presenter.submit(TOKEN, 5, "S", "B"));
    }
}
