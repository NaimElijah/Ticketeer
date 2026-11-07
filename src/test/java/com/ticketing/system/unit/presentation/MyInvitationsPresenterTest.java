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

import com.ticketing.system.shared.dto.AppointmentResponseDTO;
import com.ticketing.system.shared.dto.InvitationDTO;
import com.ticketing.system.organization.application.service.CompanyManagementService;
import com.ticketing.system.shared.exception.InvalidTokenException;
import com.ticketing.system.ui.presenters.account.MyInvitationsPresenter;
import com.ticketing.system.ui.support.ServiceErrors;
import com.ticketing.system.shared.exception.AppointmentNotFoundException;
import com.ticketing.system.shared.exception.SessionExpiredException;

class MyInvitationsPresenterTest {

    private static final LocalDateTime INVITED_AT = LocalDateTime.of(2027, 7, 15, 17, 0);

    private static final String TOKEN = "jwt-token";

    private CompanyManagementService service;
    private MyInvitationsPresenter presenter;

    @BeforeEach
    void setUp() {
        service = mock(CompanyManagementService.class);
        presenter = new MyInvitationsPresenter(service);
    }

    private static InvitationDTO invitation(int companyId, String role, String status) {
        return new InvitationDTO(
            String.valueOf(companyId), companyId, "Acme", role, "dana",
            List.of("VIEW_SALES"), status, INVITED_AT);
    }

    // -- load -----------------------------------------------------------------

    @Test
    void GivenANullToken_WhenLoad_ThenReturnsNotAuthenticatedWithoutCallingService() {
        MyInvitationsPresenter.Outcome outcome = presenter.load(null);

        assertInstanceOf(MyInvitationsPresenter.Outcome.NotAuthenticated.class, outcome);
        verify(service, never()).listMyInvitations(anyString());
    }

    @Test
    void GivenPendingAndResolvedInvitations_WhenLoad_ThenSplitsPendingFromResolvedHistory() {
        InvitationDTO pending = invitation(1, "Manager", "PENDING");
        InvitationDTO accepted = invitation(2, "Manager", "ACTIVE");
        InvitationDTO rejected = invitation(3, "Owner", "REJECTED");
        when(service.listMyInvitations(TOKEN)).thenReturn(List.of(pending, accepted, rejected));

        MyInvitationsPresenter.Outcome outcome = presenter.load(TOKEN);

        MyInvitationsPresenter.Outcome.Success ok =
            assertInstanceOf(MyInvitationsPresenter.Outcome.Success.class, outcome);
        assertEquals(List.of(pending), ok.pending());
        assertEquals(List.of(accepted, rejected), ok.history());
    }

    @Test
    void GivenTheServiceFailsInternally_WhenLoad_ThenReturnsGenericFailure() {
        when(service.listMyInvitations(TOKEN)).thenThrow(new RuntimeException("backend down"));

        MyInvitationsPresenter.Outcome outcome = presenter.load(TOKEN);

        MyInvitationsPresenter.Outcome.Failure fail =
            assertInstanceOf(MyInvitationsPresenter.Outcome.Failure.class, outcome);
        assertEquals(ServiceErrors.GENERIC_FAILURE_MESSAGE, fail.reason());
    }

    @Test
    void GivenAnInvalidToken_WhenLoad_ThenReturnsNotAuthenticated() {
        when(service.listMyInvitations(TOKEN)).thenThrow(new InvalidTokenException("bad"));

        MyInvitationsPresenter.Outcome outcome = presenter.load(TOKEN);

        assertInstanceOf(MyInvitationsPresenter.Outcome.NotAuthenticated.class, outcome);
    }

    // -- accept / decline -----------------------------------------------------

    @Test
    void GivenANullToken_WhenAccept_ThenReturnsNotAuthenticatedWithoutCallingService() {
        MyInvitationsPresenter.ActionOutcome outcome = presenter.accept(null, 7);

        assertInstanceOf(MyInvitationsPresenter.ActionOutcome.NotAuthenticated.class, outcome);
        verify(service, never()).respondToAppointment(anyString(), any());
    }

    @Test
    void GivenTheServiceAcceptsTheResponse_WhenAccept_ThenReturnsSuccessAndRespondsWithAcceptTrue() {
        MyInvitationsPresenter.ActionOutcome outcome = presenter.accept(TOKEN, 7);

        assertInstanceOf(MyInvitationsPresenter.ActionOutcome.Success.class, outcome);
        ArgumentCaptor<AppointmentResponseDTO> captor =
            ArgumentCaptor.forClass(AppointmentResponseDTO.class);
        verify(service).respondToAppointment(eq(TOKEN), captor.capture());
        assertEquals(7, captor.getValue().companyId());
        assertEquals(true, captor.getValue().accept());
    }

    @Test
    void GivenTheServiceAcceptsTheResponse_WhenDecline_ThenReturnsSuccessAndRespondsWithAcceptFalse() {
        MyInvitationsPresenter.ActionOutcome outcome = presenter.decline(TOKEN, 9);

        assertInstanceOf(MyInvitationsPresenter.ActionOutcome.Success.class, outcome);
        ArgumentCaptor<AppointmentResponseDTO> captor =
            ArgumentCaptor.forClass(AppointmentResponseDTO.class);
        verify(service).respondToAppointment(eq(TOKEN), captor.capture());
        assertEquals(9, captor.getValue().companyId());
        assertEquals(false, captor.getValue().accept());
    }

    @Test
    void GivenTheServiceFailsInternally_WhenAccept_ThenReturnsGenericFailure() {
        doThrow(new RuntimeException("no pending appointment"))
            .when(service).respondToAppointment(anyString(), any());

        MyInvitationsPresenter.ActionOutcome outcome = presenter.accept(TOKEN, 7);

        MyInvitationsPresenter.ActionOutcome.Failure fail =
            assertInstanceOf(MyInvitationsPresenter.ActionOutcome.Failure.class, outcome);
        assertEquals(ServiceErrors.GENERIC_FAILURE_MESSAGE, fail.reason());
    }

    @Test
    void GivenAnInvalidToken_WhenDecline_ThenReturnsNotAuthenticated() {
        doThrow(new InvalidTokenException("bad"))
            .when(service).respondToAppointment(anyString(), any());

        MyInvitationsPresenter.ActionOutcome outcome = presenter.decline(TOKEN, 7);

        assertInstanceOf(MyInvitationsPresenter.ActionOutcome.NotAuthenticated.class, outcome);
    }

    @Test
    void GivenNoPendingInvitation_WhenAccept_ThenReturnsFailureWithTheRulesMessage() {
        AppointmentNotFoundException rule = new AppointmentNotFoundException("No pending invitation for this company");
        doThrow(rule).when(service).respondToAppointment(anyString(), any());

        MyInvitationsPresenter.ActionOutcome.Failure fail =
            assertInstanceOf(MyInvitationsPresenter.ActionOutcome.Failure.class, presenter.accept(TOKEN, 7));
        assertEquals(rule.getMessage(), fail.reason());
    }

    // ---- an expired session reads as a dead one, as a revoked token does (ADR-025, #189) ----

    @Test
    void GivenAnExpiredSession_WhenLoad_ThenReturnsNotAuthenticated() {
        when(service.listMyInvitations(TOKEN)).thenThrow(new SessionExpiredException());

        assertInstanceOf(MyInvitationsPresenter.Outcome.NotAuthenticated.class, presenter.load(TOKEN));
    }
}
