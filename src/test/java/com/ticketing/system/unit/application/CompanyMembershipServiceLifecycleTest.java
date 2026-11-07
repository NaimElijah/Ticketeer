package com.ticketing.system.unit.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.organization.adapter.out.persistence.MemoryCompanyAppointmentRepository;
import com.ticketing.system.organization.application.service.CompanyMembershipService;
import com.ticketing.system.organization.domain.AppointmentStatus;
import com.ticketing.system.organization.domain.CompanyAppointment;
import com.ticketing.system.organization.domain.CompanyRole;
import com.ticketing.system.organization.domain.Permission;
import com.ticketing.system.shared.exception.BusinessRuleViolationException;

/**
 * Appointment-lifecycle coverage relocated from the old {@code UserTest} (task #20). These cases used
 * to exercise {@code User}'s ~17 appointment methods; they now drive {@link CompanyMembershipService}
 * (backed by an in-memory {@link MemoryCompanyAppointmentRepository}) since that behaviour was promoted
 * off the {@code User} aggregate. Same scenarios, same invariants — just keyed on {@code userId}.
 */
class CompanyMembershipServiceLifecycleTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2027-07-15T17:00:00Z"), ZoneOffset.UTC);

    private static final int USER_ID = 2;
    private static final int OWNER_ID = 1;
    private static final int COMPANY_ID = 100;
    private static final int OTHER_COMPANY_ID = 200;

    private CompanyMembershipService membership;
    private List<Permission> defaultPermissions;

    @BeforeEach
    void setUp() {
        membership = new CompanyMembershipService(new MemoryCompanyAppointmentRepository(), FIXED_CLOCK);
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
    void GivenPendingInvitation_WhenAcceptInvitation_ThenTheNowActiveInvitationIsReturned() {
        membership.receiveManagerAppointment(USER_ID, COMPANY_ID, OWNER_ID, defaultPermissions);

        CompanyAppointment appointment = membership.acceptInvitation(USER_ID, COMPANY_ID);

        assertEquals(COMPANY_ID, appointment.getCompanyId());
        assertEquals(AppointmentStatus.ACTIVE, appointment.getStatus());
        assertEquals(CompanyRole.Manager, appointment.getRole());
    }

    // The record stays, ACTIVE now, so it is no longer pending rather than removed.
    @Test
    void GivenPendingInvitation_WhenAcceptInvitation_ThenItIsNoLongerPending() {
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
    void GivenPendingInvitation_WhenAcceptInvitation_ThenTheUsersOnlyAppointmentIsActiveInThatCompany() {
        membership.receiveManagerAppointment(USER_ID, COMPANY_ID, OWNER_ID, defaultPermissions);

        membership.acceptInvitation(USER_ID, COMPANY_ID);

        List<CompanyAppointment> appointments = membership.getAllCompanyAppointments(USER_ID);
        assertEquals(1, appointments.size());
        assertEquals(COMPANY_ID, appointments.get(0).getCompanyId());
        assertEquals(AppointmentStatus.ACTIVE, appointments.get(0).getStatus());
    }

    @Test
    void GivenPendingInvitation_WhenRejectInvitation_ThenItIsNoLongerPending() {
        membership.receiveManagerAppointment(USER_ID, COMPANY_ID, OWNER_ID, defaultPermissions);

        membership.rejectInvitation(USER_ID, COMPANY_ID);

        assertNull(membership.getPendingCompanyAppointment(USER_ID, COMPANY_ID));
    }

    // The rejected record stays, so a no-op reject would leave it PENDING, not REJECTED.
    @Test
    void GivenPendingInvitation_WhenRejectInvitation_ThenTheInvitationIsRejectedAndNothingIsActive() {
        membership.receiveManagerAppointment(USER_ID, COMPANY_ID, OWNER_ID, defaultPermissions);

        membership.rejectInvitation(USER_ID, COMPANY_ID);

        assertNull(membership.getActiveCompanyAppointment(USER_ID, COMPANY_ID));
        assertEquals(List.of(AppointmentStatus.REJECTED), membership.getAllCompanyAppointments(USER_ID).stream()
                .map(CompanyAppointment::getStatus).toList());
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
    void GivenAcceptedAppointment_WhenRevokeManagerAppointment_ThenItIsNoLongerActive() {
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

    // Owners hold every permission implicitly, so an owner appointment never takes an explicit set.
    @Test
    void GivenOwnerAppointment_WhenModifyManagerPermissions_ThenRefusedAndTheOwnerHoldsNoExplicitPermissions() {
        BusinessRuleViolationException refused = assertThrows(BusinessRuleViolationException.class,
                () -> membership.modifyManagerPermissions(OWNER_ID, COMPANY_ID, OWNER_ID,
                        List.of(Permission.EDIT_POLICIES)));

        assertEquals("Can only modify permissions for manager appointments", refused.getMessage());
        assertEquals(Set.of(), membership.getActiveCompanyAppointment(OWNER_ID, COMPANY_ID).getPermissions());
    }

    // A manager must keep at least one permission; a refused edit leaves the old set in place.
    @Test
    void GivenActiveManager_WhenModifyManagerPermissionsWithNoPermissions_ThenRefusedAndTheOldPermissionsKept() {
        membership.receiveManagerAppointment(USER_ID, COMPANY_ID, OWNER_ID, defaultPermissions);
        membership.acceptInvitation(USER_ID, COMPANY_ID);

        assertThrows(IllegalArgumentException.class,
                () -> membership.modifyManagerPermissions(USER_ID, COMPANY_ID, OWNER_ID, List.of()),
                "a manager left with no permission must be refused");

        assertEquals(Set.copyOf(defaultPermissions),
                membership.getActiveCompanyAppointment(USER_ID, COMPANY_ID).getPermissions());
    }
}
