package com.ticketing.system.unit.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.ticketing.system.organization.domain.AppointmentStatus;
import com.ticketing.system.organization.domain.CompanyAppointment;
import com.ticketing.system.organization.domain.CompanyRole;
import com.ticketing.system.organization.domain.Permission;
import com.ticketing.system.shared.exception.InvalidPermissionException;

/**
 * {@link CompanyAppointment} owns its permission set: only {@code updatePermissions}, which checks the
 * invariants, may change it. A caller holding the set returned by {@code getPermissions} must not be
 * able to empty a manager's permissions or grant itself new ones (#187).
 */
class CompanyAppointmentTest {

    private static final int APPOINTMENT_ID = 1;
    private static final int COMPANY_ID = 100;
    private static final int MANAGER_ID = 2;
    private static final int OWNER_ID = 1;
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2027, 7, 15, 17, 0);
    private static final Set<Permission> GRANTED = Set.of(Permission.VIEW_SALES, Permission.MANAGE_INVENTORY);

    private static CompanyAppointment activeManagerAppointment() {
        CompanyAppointment appointment = CompanyAppointment.ManagerAppointment(APPOINTMENT_ID, COMPANY_ID,
                MANAGER_ID, OWNER_ID, List.copyOf(GRANTED), CREATED_AT);
        appointment.accept();
        return appointment;
    }

    @Test
    void GivenActiveManagerAppointment_WhenCallerClearsTheReturnedPermissions_ThenRefusedAndThePermissionsKept() {
        CompanyAppointment appointment = activeManagerAppointment();
        Set<Permission> returned = appointment.getPermissions();

        assertThrows(UnsupportedOperationException.class, returned::clear,
                "the returned permission set must not let a caller empty a manager's permissions");

        assertEquals(GRANTED, appointment.getPermissions());
    }

    @Test
    void GivenActiveManagerAppointment_WhenCallerAddsToTheReturnedPermissions_ThenRefusedAndThePermissionsKept() {
        CompanyAppointment appointment = activeManagerAppointment();
        Set<Permission> returned = appointment.getPermissions();

        assertThrows(UnsupportedOperationException.class, () -> returned.add(Permission.EDIT_POLICIES),
                "the returned permission set must not let a caller grant a manager a permission");

        assertEquals(GRANTED, appointment.getPermissions());
    }

    // ---- permission sets are copied on the way in (#187) ----

    @Test
    void GivenPermissionsUpdatedFromACallersSet_WhenTheCallerChangesThatSet_ThenTheAppointmentsPermissionsAreUnchanged() {
        CompanyAppointment appointment = activeManagerAppointment();
        Set<Permission> callerSet = EnumSet.of(Permission.EDIT_POLICIES);
        appointment.updatePermissions(callerSet);

        callerSet.add(Permission.CONFIGURE_VENUE);
        callerSet.remove(Permission.EDIT_POLICIES);

        assertEquals(Set.of(Permission.EDIT_POLICIES), appointment.getPermissions(),
                "the appointment must keep its own copy, not the caller's collection");
    }

    // ---- who an appointment authorises (#189) ----
    // CompanyMembershipService looks up only active appointments, so these rules are reached directly here.

    private static final int CO_OWNER_ID = 4;
    private static final int STRANGER_ID = 9;

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = AppointmentStatus.class, names = {"PENDING", "REJECTED", "REVOKED"})
    void GivenAManagerAppointmentThatIsNotActive_WhenHasAGrantedPermission_ThenFalse(AppointmentStatus status) {
        CompanyAppointment appointment = new CompanyAppointment(APPOINTMENT_ID, COMPANY_ID, MANAGER_ID, OWNER_ID,
                CompanyRole.Manager, status, List.copyOf(GRANTED), CREATED_AT);

        assertFalse(appointment.hasPermission(Permission.VIEW_SALES),
                "a " + status + " manager must not be granted a permission it holds on paper");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = AppointmentStatus.class, names = {"PENDING", "REJECTED", "REVOKED"})
    void GivenAnOwnerAppointmentThatIsNotActive_WhenHasPermission_ThenFalse(AppointmentStatus status) {
        CompanyAppointment appointment = new CompanyAppointment(APPOINTMENT_ID, COMPANY_ID, CO_OWNER_ID, OWNER_ID,
                CompanyRole.Owner, status, null, CREATED_AT);

        assertFalse(appointment.hasPermission(Permission.EDIT_POLICIES));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Permission.class)
    void GivenAnActiveOwner_WhenHasPermission_ThenTrue(Permission permission) {
        CompanyAppointment owner = activeOwnerAppointment();

        assertTrue(owner.hasPermission(permission), "an owner holds every permission, " + permission + " too");
    }

    @Test
    void GivenAnActiveManager_WhenHasPermission_ThenTrueOnlyForTheGrantedOnes() {
        CompanyAppointment manager = activeManagerAppointment();

        Set<Permission> held = EnumSet.noneOf(Permission.class);
        for (Permission permission : Permission.values()) {
            if (manager.hasPermission(permission)) {
                held.add(permission);
            }
        }

        assertEquals(GRANTED, held);
    }

    @Test
    void GivenAnActiveManager_WhenRevokedByItsInviter_ThenRevoked() {
        CompanyAppointment manager = activeManagerAppointment();

        manager.revoke(OWNER_ID);

        assertEquals(AppointmentStatus.REVOKED, manager.getStatus());
    }

    @Test
    void GivenAnActiveManager_WhenRevokedByAnotherOwner_ThenRefusedAndStaysActive() {
        CompanyAppointment manager = activeManagerAppointment();

        assertThrows(IllegalArgumentException.class, () -> manager.revoke(CO_OWNER_ID));

        assertEquals(AppointmentStatus.ACTIVE, manager.getStatus());
    }

    @Test
    void GivenAnActiveManager_WhenRevokedByThemselves_ThenRefusedAndStaysActive() {
        CompanyAppointment manager = activeManagerAppointment();

        assertThrows(IllegalArgumentException.class, () -> manager.revoke(MANAGER_ID));

        assertEquals(AppointmentStatus.ACTIVE, manager.getStatus());
    }

    @Test
    void GivenAnActiveOwnerAppointment_WhenRevokedByItsTarget_ThenRevoked() {
        CompanyAppointment owner = activeOwnerAppointment();

        owner.revoke(CO_OWNER_ID);

        assertEquals(AppointmentStatus.REVOKED, owner.getStatus());
    }

    @Test
    void GivenAnActiveOwnerAppointment_WhenRevokedByItsInviter_ThenRevoked() {
        CompanyAppointment owner = activeOwnerAppointment();

        owner.revoke(OWNER_ID);

        assertEquals(AppointmentStatus.REVOKED, owner.getStatus());
    }

    @Test
    void GivenAnActiveOwnerAppointment_WhenRevokedByAStranger_ThenRefusedAndStaysActive() {
        CompanyAppointment owner = activeOwnerAppointment();

        assertThrows(IllegalArgumentException.class, () -> owner.revoke(STRANGER_ID));

        assertEquals(AppointmentStatus.ACTIVE, owner.getStatus());
    }

    @Test
    void GivenAPendingManagerAppointment_WhenRevokedByItsInviter_ThenRefusedAndStaysPending() {
        CompanyAppointment pending = pendingManagerAppointment();

        assertThrows(IllegalStateException.class, () -> pending.revoke(OWNER_ID));

        assertEquals(AppointmentStatus.PENDING, pending.getStatus());
    }

    @Test
    void GivenAPendingAppointment_WhenRejected_ThenRejected() {
        CompanyAppointment pending = pendingManagerAppointment();

        pending.reject();

        assertEquals(AppointmentStatus.REJECTED, pending.getStatus());
    }

    @Test
    void GivenAnAcceptedAppointment_WhenRejected_ThenRefusedAndStaysActive() {
        CompanyAppointment manager = activeManagerAppointment();

        assertThrows(IllegalStateException.class, manager::reject);

        assertEquals(AppointmentStatus.ACTIVE, manager.getStatus());
    }

    @Test
    void GivenAnAcceptedAppointment_WhenAcceptedAgain_ThenRefusedAndStaysActive() {
        CompanyAppointment manager = activeManagerAppointment();

        assertThrows(IllegalStateException.class, manager::accept);

        assertEquals(AppointmentStatus.ACTIVE, manager.getStatus());
    }

    @Test
    void GivenARejectedAppointment_WhenAccepted_ThenRefusedAndStaysRejected() {
        CompanyAppointment rejected = pendingManagerAppointment();
        rejected.reject();

        assertThrows(IllegalStateException.class, rejected::accept);

        assertEquals(AppointmentStatus.REJECTED, rejected.getStatus());
    }

    @Test
    void GivenAPendingManager_WhenUpdatePermissions_ThenRefusedAndThePermissionsKept() {
        CompanyAppointment pending = pendingManagerAppointment();

        assertThrows(IllegalStateException.class, () -> pending.updatePermissions(Set.of(Permission.EDIT_POLICIES)));

        assertEquals(GRANTED, pending.getPermissions());
    }

    @Test
    void GivenAnActiveOwner_WhenUpdatePermissions_ThenRefusedAndItHoldsNoExplicitPermission() {
        CompanyAppointment owner = activeOwnerAppointment();

        assertThrows(InvalidPermissionException.class, () -> owner.updatePermissions(Set.of(Permission.VIEW_SALES)));

        assertTrue(owner.getPermissions().isEmpty());
    }

    private static CompanyAppointment pendingManagerAppointment() {
        return CompanyAppointment.ManagerAppointment(APPOINTMENT_ID, COMPANY_ID, MANAGER_ID, OWNER_ID,
                List.copyOf(GRANTED), CREATED_AT);
    }

    private static CompanyAppointment activeOwnerAppointment() {
        CompanyAppointment owner = CompanyAppointment.OwnerAppointment(APPOINTMENT_ID, COMPANY_ID, CO_OWNER_ID,
                OWNER_ID, CREATED_AT);
        owner.accept();
        return owner;
    }
}
