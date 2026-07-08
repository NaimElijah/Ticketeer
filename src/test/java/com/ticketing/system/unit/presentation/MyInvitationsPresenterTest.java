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

import com.ticketing.system.Core.Application.dto.AppointmentResponseDTO;
import com.ticketing.system.Core.Application.dto.InvitationDTO;
import com.ticketing.system.organization.application.service.CompanyManagementService;
import com.ticketing.system.shared.exception.InvalidTokenException;
import com.ticketing.system.ui.presenters.account.MyInvitationsPresenter;

class MyInvitationsPresenterTest {

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
            List.of("VIEW_SALES"), status, LocalDateTime.now());
    }

    // -- load -----------------------------------------------------------------

    @Test
    void load_nullToken_returnsNotAuthenticated_withoutCallingService() {
        MyInvitationsPresenter.Outcome outcome = presenter.load(null);

        assertInstanceOf(MyInvitationsPresenter.Outcome.NotAuthenticated.class, outcome);
        verify(service, never()).listMyInvitations(anyString());
    }

    @Test
    void load_splitsPendingFromResolvedHistory() {
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
    void load_serviceThrows_returnsFailureWithMessage() {
        when(service.listMyInvitations(TOKEN)).thenThrow(new RuntimeException("backend down"));

        MyInvitationsPresenter.Outcome outcome = presenter.load(TOKEN);

        MyInvitationsPresenter.Outcome.Failure fail =
            assertInstanceOf(MyInvitationsPresenter.Outcome.Failure.class, outcome);
        assertEquals("backend down", fail.reason());
    }

    @Test
    void load_invalidToken_returnsNotAuthenticated() {
        when(service.listMyInvitations(TOKEN)).thenThrow(new InvalidTokenException("bad"));

        MyInvitationsPresenter.Outcome outcome = presenter.load(TOKEN);

        assertInstanceOf(MyInvitationsPresenter.Outcome.NotAuthenticated.class, outcome);
    }

    // -- accept / decline -----------------------------------------------------

    @Test
    void accept_nullToken_returnsNotAuthenticated_withoutCallingService() {
        MyInvitationsPresenter.ActionOutcome outcome = presenter.accept(null, 7);

        assertInstanceOf(MyInvitationsPresenter.ActionOutcome.NotAuthenticated.class, outcome);
        verify(service, never()).respondToAppointment(anyString(), any());
    }

    @Test
    void accept_happyPath_returnsSuccess_andRespondsWithAcceptTrue() {
        MyInvitationsPresenter.ActionOutcome outcome = presenter.accept(TOKEN, 7);

        assertInstanceOf(MyInvitationsPresenter.ActionOutcome.Success.class, outcome);
        ArgumentCaptor<AppointmentResponseDTO> captor =
            ArgumentCaptor.forClass(AppointmentResponseDTO.class);
        verify(service).respondToAppointment(eq(TOKEN), captor.capture());
        assertEquals(7, captor.getValue().companyId());
        assertEquals(true, captor.getValue().accept());
    }

    @Test
    void decline_happyPath_returnsSuccess_andRespondsWithAcceptFalse() {
        MyInvitationsPresenter.ActionOutcome outcome = presenter.decline(TOKEN, 9);

        assertInstanceOf(MyInvitationsPresenter.ActionOutcome.Success.class, outcome);
        ArgumentCaptor<AppointmentResponseDTO> captor =
            ArgumentCaptor.forClass(AppointmentResponseDTO.class);
        verify(service).respondToAppointment(eq(TOKEN), captor.capture());
        assertEquals(9, captor.getValue().companyId());
        assertEquals(false, captor.getValue().accept());
    }

    @Test
    void accept_serviceThrows_returnsFailureWithMessage() {
        doThrow(new RuntimeException("no pending appointment"))
            .when(service).respondToAppointment(anyString(), any());

        MyInvitationsPresenter.ActionOutcome outcome = presenter.accept(TOKEN, 7);

        MyInvitationsPresenter.ActionOutcome.Failure fail =
            assertInstanceOf(MyInvitationsPresenter.ActionOutcome.Failure.class, outcome);
        assertEquals("no pending appointment", fail.reason());
    }

    @Test
    void decline_invalidToken_returnsNotAuthenticated() {
        doThrow(new InvalidTokenException("bad"))
            .when(service).respondToAppointment(anyString(), any());

        MyInvitationsPresenter.ActionOutcome outcome = presenter.decline(TOKEN, 7);

        assertInstanceOf(MyInvitationsPresenter.ActionOutcome.NotAuthenticated.class, outcome);
    }
}
