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

import com.ticketing.system.shared.dto.AppointmentInfoDTO;
import com.ticketing.system.shared.dto.AppointmentRevokeDTO;
import com.ticketing.system.organization.application.dto.PermissionEditDTO;
import com.ticketing.system.organization.application.dto.ProductionCompanyDTO;
import com.ticketing.system.organization.application.service.CompanyManagementService;
import com.ticketing.system.shared.exception.InvalidTokenException;
import com.ticketing.system.organization.domain.Permission;
import com.ticketing.system.ui.presenters.company.ManagerListPresenter;

class ManagerListPresenterTest {

    private static final String TOKEN = "jwt-token";

    private CompanyManagementService service;
    private ManagerListPresenter presenter;

    @BeforeEach
    void setUp() {
        service = mock(CompanyManagementService.class);
        presenter = new ManagerListPresenter(service);
    }

    @Test
    void nullToken_returnsNotAuthenticated_withoutCallingService() {
        ManagerListPresenter.Outcome outcome = presenter.loadRoster(null);

        assertInstanceOf(ManagerListPresenter.Outcome.NotAuthenticated.class, outcome);
        verify(service, never()).findOwnedCompanies(anyString());
    }

    @Test
    void noOwnedCompany_returnsNoCompany() {
        when(service.findOwnedCompanies(TOKEN)).thenReturn(List.of());

        ManagerListPresenter.Outcome outcome = presenter.loadRoster(TOKEN);

        assertInstanceOf(ManagerListPresenter.Outcome.NoCompany.class, outcome);
        verify(service, never()).listManagers(anyString(), anyInt());
    }

    @Test
    void ownedCompany_returnsSuccessWithBothRosters() {
        ProductionCompanyDTO company = new ProductionCompanyDTO(7, "Acme", "desc", "ACTIVE", 1);
        AppointmentInfoDTO manager = new AppointmentInfoDTO(
            "10", 7, "Acme", 2, "carol", 1, "Manager", "ACTIVE",
            List.of("VIEW_SALES"), LocalDateTime.now());
        AppointmentInfoDTO pending = new AppointmentInfoDTO(
            "11", 7, "Acme", 3, "yossi", 1, "Manager", "PENDING",
            List.of("MANAGE_INVENTORY"), LocalDateTime.now());

        when(service.findOwnedCompanies(TOKEN)).thenReturn(List.of(company));
        when(service.listManagers(TOKEN, 7)).thenReturn(List.of(manager));
        when(service.listPendingInvitations(TOKEN, 7)).thenReturn(List.of(pending));

        ManagerListPresenter.Outcome outcome = presenter.loadRoster(TOKEN);

        ManagerListPresenter.Outcome.Success ok =
            assertInstanceOf(ManagerListPresenter.Outcome.Success.class, outcome);
        assertEquals("Acme", ok.companyName());
        assertEquals(List.of(manager), ok.activeManagers());
        assertEquals(List.of(pending), ok.pendingInvitations());
    }

    @Test
    void serviceThrows_returnsFailureWithMessage() {
        when(service.findOwnedCompanies(TOKEN)).thenThrow(new RuntimeException("backend down"));

        ManagerListPresenter.Outcome outcome = presenter.loadRoster(TOKEN);

        ManagerListPresenter.Outcome.Failure fail =
            assertInstanceOf(ManagerListPresenter.Outcome.Failure.class, outcome);
        assertEquals("backend down", fail.reason());
    }

    // -- editPermissions ------------------------------------------------------

    @Test
    void editPermissions_nullToken_returnsNotAuthenticated_withoutCallingService() {
        ManagerListPresenter.ActionOutcome outcome =
            presenter.editPermissions(null, 7, 2, List.of("VIEW_SALES"));

        assertInstanceOf(ManagerListPresenter.ActionOutcome.NotAuthenticated.class, outcome);
        verify(service, never()).editManagerPermissions(anyString(), any());
    }

    @Test
    void editPermissions_happyPath_returnsSuccess_andBuildsDtoWithConvertedPermissions() {
        ManagerListPresenter.ActionOutcome outcome = presenter.editPermissions(
            TOKEN, 7, 2, List.of("VIEW_SALES", "EDIT_POLICIES"));

        assertInstanceOf(ManagerListPresenter.ActionOutcome.Success.class, outcome);
        ArgumentCaptor<PermissionEditDTO> captor = ArgumentCaptor.forClass(PermissionEditDTO.class);
        verify(service).editManagerPermissions(eq(TOKEN), captor.capture());
        PermissionEditDTO dto = captor.getValue();
        assertEquals(7, dto.companyId());
        assertEquals(2, dto.targetUserId());
        assertEquals(List.of(Permission.VIEW_SALES, Permission.EDIT_POLICIES), dto.newPermissions());
    }

    @Test
    void editPermissions_serviceThrows_returnsFailureWithMessage() {
        doThrow(new RuntimeException("only the appointer may edit"))
            .when(service).editManagerPermissions(anyString(), any());

        ManagerListPresenter.ActionOutcome outcome =
            presenter.editPermissions(TOKEN, 7, 2, List.of("VIEW_SALES"));

        ManagerListPresenter.ActionOutcome.Failure fail =
            assertInstanceOf(ManagerListPresenter.ActionOutcome.Failure.class, outcome);
        assertEquals("only the appointer may edit", fail.reason());
    }

    @Test
    void editPermissions_invalidToken_returnsNotAuthenticated() {
        doThrow(new InvalidTokenException("bad"))
            .when(service).editManagerPermissions(anyString(), any());

        ManagerListPresenter.ActionOutcome outcome =
            presenter.editPermissions(TOKEN, 7, 2, List.of("VIEW_SALES"));

        assertInstanceOf(ManagerListPresenter.ActionOutcome.NotAuthenticated.class, outcome);
    }

    // -- revoke ---------------------------------------------------------------

    @Test
    void revoke_nullToken_returnsNotAuthenticated_withoutCallingService() {
        ManagerListPresenter.ActionOutcome outcome = presenter.revoke(null, 7, 2);

        assertInstanceOf(ManagerListPresenter.ActionOutcome.NotAuthenticated.class, outcome);
        verify(service, never()).RevokeAppointment(anyString(), any());
    }

    @Test
    void revoke_happyPath_returnsSuccess_andBuildsDto() {
        ManagerListPresenter.ActionOutcome outcome = presenter.revoke(TOKEN, 7, 2);

        assertInstanceOf(ManagerListPresenter.ActionOutcome.Success.class, outcome);
        ArgumentCaptor<AppointmentRevokeDTO> captor =
            ArgumentCaptor.forClass(AppointmentRevokeDTO.class);
        verify(service).RevokeAppointment(eq(TOKEN), captor.capture());
        AppointmentRevokeDTO dto = captor.getValue();
        assertEquals(7, dto.companyId());
        assertEquals(2, dto.targetUserId());
    }

    @Test
    void revoke_serviceThrows_returnsFailureWithMessage() {
        doThrow(new RuntimeException("Cannot revoke appointment of the founder"))
            .when(service).RevokeAppointment(anyString(), any());

        ManagerListPresenter.ActionOutcome outcome = presenter.revoke(TOKEN, 7, 2);

        ManagerListPresenter.ActionOutcome.Failure fail =
            assertInstanceOf(ManagerListPresenter.ActionOutcome.Failure.class, outcome);
        assertEquals("Cannot revoke appointment of the founder", fail.reason());
    }

    @Test
    void revoke_invalidToken_returnsNotAuthenticated() {
        doThrow(new InvalidTokenException("bad"))
            .when(service).RevokeAppointment(anyString(), any());

        ManagerListPresenter.ActionOutcome outcome = presenter.revoke(TOKEN, 7, 2);

        assertInstanceOf(ManagerListPresenter.ActionOutcome.NotAuthenticated.class, outcome);
    }
}
