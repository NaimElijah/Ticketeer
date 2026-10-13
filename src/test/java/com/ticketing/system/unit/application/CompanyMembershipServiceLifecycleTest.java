package com.ticketing.system.unit.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.organization.adapter.out.persistence.MemoryCompanyAppointmentRepository;
import com.ticketing.system.organization.application.service.CompanyMembershipService;
import com.ticketing.system.organization.domain.CompanyAppointment;
import com.ticketing.system.organization.domain.Permission;

/**
 * Appointment-lifecycle coverage relocated from the old {@code UserTest} (task #20). These cases used
 * to exercise {@code User}'s ~17 appointment methods; they now drive {@link CompanyMembershipService}
 * (backed by an in-memory {@link MemoryCompanyAppointmentRepository}) since that behaviour was promoted
 * off the {@code User} aggregate. Same scenarios, same invariants — just keyed on {@code userId}.
 */
class CompanyMembershipServiceLifecycleTest {

    private static final int USER_ID = 2;
    private static final int OWNER_ID = 1;
    private static final int COMPANY_ID = 100;
    private static final int OTHER_COMPANY_ID = 200;

    private CompanyMembershipService membership;
    private List<Permission> defaultPermissions;

    @BeforeEach
    void setUp() {
        membership = new CompanyMembershipService(new MemoryCompanyAppointmentRepository());
        defaultPermissions = new ArrayList<>();
        defaultPermissions.add(Permission.CONFIGURE_VENUE);
        defaultPermissions.add(Permission.MANAGE_INVENTORY);
        // The owner founds the company (immediately-active owner appointment).
        membership.addFounderAppointment(OWNER_ID, COMPANY_ID);
    }

    @Test
    void GivenUser_WhenInvitedToCompanyAppointment_ThenUserHasOneInvitation() {
        membership.receiveManagerAppointment(USER_ID, COMPANY_ID, OWNER_ID, defaultPermissions);

        assertEquals(1, membership.getAllCompanyAppointments(USER_ID).size());
    }

    @Test
    void GivenPendingInvitation_WhenAcceptInvitation_ThenInvitationReturned() {
        membership.receiveManagerAppointment(USER_ID, COMPANY_ID, OWNER_ID, defaultPermissions);

        CompanyAppointment appointment = membership.acceptInvitation(USER_ID, COMPANY_ID);

        assertEquals(COMPANY_ID, appointment.getCompanyId());
    }

    @Test
    void GivenPendingInvitation_WhenAcceptInvitation_ThenInvitationRemoved() {
        membership.receiveManagerAppointment(USER_ID, COMPANY_ID, OWNER_ID, defaultPermissions);

        membership.acceptInvitation(USER_ID, COMPANY_ID);

        assertNull(membership.getPendingCompanyAppointment(USER_ID, COMPANY_ID));
    }

    @Test
    void GivenPendingInvitation_WhenAcceptInvitation_ThenCompanyAppointmentCreated() {
        membership.receiveManagerAppointment(USER_ID, COMPANY_ID, OWNER_ID, defaultPermissions);

        membership.acceptInvitation(USER_ID, COMPANY_ID);

        assertNotEquals(null, membership.getActiveCompanyAppointment(USER_ID, COMPANY_ID));
    }

    @Test
    void GivenPendingInvitation_WhenAcceptInvitation_ThenAppointmentHasCorrectCompanyId() {
        membership.receiveManagerAppointment(USER_ID, COMPANY_ID, OWNER_ID, defaultPermissions);

        membership.acceptInvitation(USER_ID, COMPANY_ID);

        CompanyAppointment appointment = membership.getAllCompanyAppointments(USER_ID).get(0);

        assertEquals(COMPANY_ID, appointment.getCompanyId());
    }

    @Test
    void GivenPendingInvitation_WhenRejectInvitation_ThenInvitationRemoved() {
        membership.receiveManagerAppointment(USER_ID, COMPANY_ID, OWNER_ID, defaultPermissions);

        membership.rejectInvitation(USER_ID, COMPANY_ID);

        assertNull(membership.getPendingCompanyAppointment(USER_ID, COMPANY_ID));
    }

    @Test
    void GivenPendingInvitation_WhenRejectInvitation_ThenNoCompanyAppointmentCreated() {
        membership.receiveManagerAppointment(USER_ID, COMPANY_ID, OWNER_ID, defaultPermissions);

        membership.rejectInvitation(USER_ID, COMPANY_ID);

        assertNull(membership.getActiveCompanyAppointment(USER_ID, COMPANY_ID));
    }

    @Test
    void GivenNoInvitation_WhenAcceptInvitation_ThenThrowException() {
        assertThrows(RuntimeException.class, () -> membership.acceptInvitation(USER_ID, COMPANY_ID));
    }

    @Test
    void GivenNoInvitation_WhenRejectInvitation_ThenThrowException() {
        assertThrows(RuntimeException.class, () -> membership.rejectInvitation(USER_ID, COMPANY_ID));
    }

    @Test
    void GivenInvitationForOtherCompany_WhenAcceptInvitation_ThenThrowException() {
        membership.receiveManagerAppointment(USER_ID, OTHER_COMPANY_ID, OWNER_ID, defaultPermissions);

        assertThrows(RuntimeException.class, () -> membership.acceptInvitation(USER_ID, COMPANY_ID));
    }

    @Test
    void GivenInvitationForOtherCompany_WhenRejectInvitation_ThenThrowException() {
        membership.receiveManagerAppointment(USER_ID, OTHER_COMPANY_ID, OWNER_ID, defaultPermissions);

        assertThrows(RuntimeException.class, () -> membership.rejectInvitation(USER_ID, COMPANY_ID));
    }

    @Test
    void GivenAcceptedAppointment_WhenRevokeManagerAppointment_ThenAppointmentRemoved() {
        membership.receiveManagerAppointment(USER_ID, COMPANY_ID, OWNER_ID, defaultPermissions);
        membership.acceptInvitation(USER_ID, COMPANY_ID);

        membership.revokeAppointment(USER_ID, COMPANY_ID, OWNER_ID);

        assertNull(membership.getActiveCompanyAppointment(USER_ID, COMPANY_ID));
    }

    @Test
    void GivenNoAppointment_WhenRevokeManagerAppointment_ThenThrowException() {
        assertThrows(RuntimeException.class, () -> membership.revokeAppointment(USER_ID, COMPANY_ID, OWNER_ID));
    }

    @Test
    void GivenAppointmentForOtherCompany_WhenRevokeManagerAppointment_ThenThrowException() {
        membership.receiveManagerAppointment(USER_ID, OTHER_COMPANY_ID, OWNER_ID, defaultPermissions);
        membership.acceptInvitation(USER_ID, OTHER_COMPANY_ID);

        assertThrows(RuntimeException.class, () -> membership.revokeAppointment(USER_ID, COMPANY_ID, OWNER_ID));
    }

    @Test
    void GivenAcceptedAppointment_WhenModifyManagerPermissions_ThenPermissionsUpdated() {
        membership.receiveManagerAppointment(USER_ID, COMPANY_ID, OWNER_ID, defaultPermissions);
        membership.acceptInvitation(USER_ID, COMPANY_ID);

        List<Permission> newPermissions = new ArrayList<>();
        newPermissions.add(Permission.EDIT_POLICIES);

        membership.modifyManagerPermissions(USER_ID, COMPANY_ID, OWNER_ID, newPermissions);

        CompanyAppointment appointment = membership.getActiveCompanyAppointment(USER_ID, COMPANY_ID);

        assertEquals(newPermissions, appointment.getPermissions().stream().toList());
    }

    @Test
    void GivenNoAppointment_WhenModifyManagerPermissions_ThenThrowException() {
        List<Permission> newPermissions = new ArrayList<>();
        newPermissions.add(Permission.EDIT_POLICIES);

        assertThrows(RuntimeException.class,
                () -> membership.modifyManagerPermissions(USER_ID, COMPANY_ID, USER_ID, newPermissions));
    }

    @Test
    void GivenAppointmentForOtherCompany_WhenModifyManagerPermissions_ThenThrowException() {
        membership.receiveManagerAppointment(USER_ID, OTHER_COMPANY_ID, OWNER_ID, defaultPermissions);
        membership.acceptInvitation(USER_ID, OTHER_COMPANY_ID);

        List<Permission> newPermissions = new ArrayList<>();
        newPermissions.add(Permission.EDIT_POLICIES);

        assertThrows(RuntimeException.class,
                () -> membership.modifyManagerPermissions(USER_ID, COMPANY_ID, USER_ID, newPermissions));
    }
}
