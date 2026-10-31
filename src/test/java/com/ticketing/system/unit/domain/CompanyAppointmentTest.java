package com.ticketing.system.unit.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.ticketing.system.organization.domain.CompanyAppointment;
import com.ticketing.system.organization.domain.Permission;

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
}
