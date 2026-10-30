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

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.ticketing.system.organization.application.dto.ManagerAppointmentRequestDTO;
import com.ticketing.system.organization.application.dto.ProductionCompanyDTO;
import com.ticketing.system.organization.application.service.CompanyManagementService;
import com.ticketing.system.organization.domain.Permission;
import com.ticketing.system.ui.presenters.company.ManagerInvitationPresenter;
import com.ticketing.system.ui.session.CurrentCompanies;
import com.ticketing.system.ui.session.MapBackedVaadinSession;

/**
 * {@link ManagerInvitationPresenter#invite} appoints the manager in the company the owner has
 * selected in the workspace ({@link CurrentCompanies}), the same company the capability gate checked.
 * {@code findOwnedCompanies} is unordered, so "the first owned company" can be any of them. The
 * selection is honoured only when the caller owns it. An owner of several companies with none
 * selected is asked to choose one; an owner of none has no company to invite into.
 */
class ManagerInvitationPresenterTest {

    private static final String TOKEN = "owner-jwt";
    private static final String INVITEE = "carol";
    private static final int INVITEE_USER_ID = 5;
    private static final List<Permission> PERMISSIONS = List.of(Permission.VIEW_SALES);
    private static final int FIRST_COMPANY_ID = 7;
    private static final int SECOND_COMPANY_ID = 9;
    private static final int UNOWNED_COMPANY_ID = 11;
    private static final int FOUNDER_ID = 1;

    private CompanyManagementService service;
    private ManagerInvitationPresenter presenter;

    @BeforeEach
    void setUp() {
        MapBackedVaadinSession.install();
        service = mock(CompanyManagementService.class);
        presenter = new ManagerInvitationPresenter(service);
        when(service.findOwnedCompanies(TOKEN)).thenReturn(List.of(
                company(FIRST_COMPANY_ID, "Alpha"), company(SECOND_COMPANY_ID, "Beta")));
        when(service.resolveUserId(INVITEE)).thenReturn(INVITEE_USER_ID);
    }

    @AfterEach
    void tearDown() {
        MapBackedVaadinSession.uninstall();
    }

    @Test
    void GivenOwnerOfTwoCompaniesWithTheSecondSelected_WhenInvite_ThenTheAppointmentIsInTheSelectedCompany() {
        CurrentCompanies.setCurrentCompany(SECOND_COMPANY_ID);

        ManagerInvitationPresenter.Outcome outcome = presenter.invite(TOKEN, INVITEE, PERMISSIONS);

        assertInstanceOf(ManagerInvitationPresenter.Outcome.Success.class, outcome);
        ArgumentCaptor<ManagerAppointmentRequestDTO> request =
                ArgumentCaptor.forClass(ManagerAppointmentRequestDTO.class);
        verify(service).appointManager(eq(TOKEN), request.capture());
        assertEquals(SECOND_COMPANY_ID, request.getValue().companyId(),
                "the manager must be appointed in the selected company, not the first owned one");
    }

    @Test
    void GivenOwnerOfTwoCompaniesAndNoSelection_WhenInvite_ThenChooseCompanyAndNoAppointment() {
        ManagerInvitationPresenter.Outcome outcome = presenter.invite(TOKEN, INVITEE, PERMISSIONS);

        assertInstanceOf(ManagerInvitationPresenter.Outcome.ChooseCompany.class, outcome);
        verify(service, never()).appointManager(anyString(), any());
    }

    @Test
    void GivenOwnerOfNoCompany_WhenInvite_ThenNoCompanyAndNoAppointment() {
        when(service.findOwnedCompanies(TOKEN)).thenReturn(List.of());

        ManagerInvitationPresenter.Outcome outcome = presenter.invite(TOKEN, INVITEE, PERMISSIONS);

        assertInstanceOf(ManagerInvitationPresenter.Outcome.NoCompany.class, outcome);
        verify(service, never()).appointManager(anyString(), any());
    }

    // With one owned company there is nothing to choose, so an owner who never picked one in the
    // workspace still invites into it; only a choice between several needs a selection.
    @Test
    void GivenOwnerOfOneCompanyAndNoSelection_WhenInvite_ThenTheAppointmentIsInThatCompany() {
        when(service.findOwnedCompanies(TOKEN)).thenReturn(List.of(company(FIRST_COMPANY_ID, "Alpha")));

        ManagerInvitationPresenter.Outcome outcome = presenter.invite(TOKEN, INVITEE, PERMISSIONS);

        assertInstanceOf(ManagerInvitationPresenter.Outcome.Success.class, outcome);
        ArgumentCaptor<ManagerAppointmentRequestDTO> request =
                ArgumentCaptor.forClass(ManagerAppointmentRequestDTO.class);
        verify(service).appointManager(eq(TOKEN), request.capture());
        assertEquals(FIRST_COMPANY_ID, request.getValue().companyId());
    }

    // A selection the caller does not own is no selection: an owner still has one of theirs to choose.
    @Test
    void GivenASelectedCompanyTheCallerDoesNotOwn_WhenInvite_ThenChooseCompanyAndNoAppointment() {
        CurrentCompanies.setCurrentCompany(UNOWNED_COMPANY_ID);

        ManagerInvitationPresenter.Outcome outcome = presenter.invite(TOKEN, INVITEE, PERMISSIONS);

        assertInstanceOf(ManagerInvitationPresenter.Outcome.ChooseCompany.class, outcome);
        verify(service, never()).appointManager(anyString(), any());
    }

    @Test
    void GivenASelectedCompanyAndACallerWhoOwnsNone_WhenInvite_ThenNoCompanyAndNoAppointment() {
        CurrentCompanies.setCurrentCompany(UNOWNED_COMPANY_ID);
        when(service.findOwnedCompanies(TOKEN)).thenReturn(List.of());

        ManagerInvitationPresenter.Outcome outcome = presenter.invite(TOKEN, INVITEE, PERMISSIONS);

        assertInstanceOf(ManagerInvitationPresenter.Outcome.NoCompany.class, outcome);
        verify(service, never()).appointManager(anyString(), any());
    }

    private static ProductionCompanyDTO company(int id, String name) {
        return new ProductionCompanyDTO(id, name, "desc", "ACTIVE", FOUNDER_ID);
    }
}
