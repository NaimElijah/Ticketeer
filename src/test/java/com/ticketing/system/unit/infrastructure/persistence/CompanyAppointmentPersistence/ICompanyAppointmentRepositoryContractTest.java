package com.ticketing.system.unit.infrastructure.persistence.CompanyAppointmentPersistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.organization.application.port.out.CompanyAppointmentRepository;
import com.ticketing.system.organization.domain.AppointmentStatus;
import com.ticketing.system.organization.domain.CompanyAppointment;
import com.ticketing.system.organization.domain.CompanyRole;
import com.ticketing.system.organization.domain.Permission;

/**
 * Contract every {@link CompanyAppointmentRepository} implementation must satisfy. The Memory and JPA
 * adapters each subclass this with their own {@link #newRepository()} factory; the tests are reused.
 * This is where the appointment-persistence coverage lives now that {@code CompanyAppointment} is a
 * standalone aggregate rather than an owned child of {@code User} (task #20) — it pins that role,
 * status and the granted permission set survive save/reload on both backends, and that appointments
 * are queryable by the appointed user's id ({@code targetId}) and by company.
 */
abstract class ICompanyAppointmentRepositoryContractTest {

    protected abstract CompanyAppointmentRepository newRepository();

    private CompanyAppointmentRepository repo;

    @BeforeEach
    void setUp() {
        repo = newRepository();
    }

    // Helper — mints an id and builds a PENDING manager appointment with the given permissions.
    private CompanyAppointment manager(int companyId, int targetId, int inviterId, List<Permission> permissions) {
        return CompanyAppointment.ManagerAppointment(repo.nextId(), companyId, targetId, inviterId, permissions);
    }

    @Test
    void save_thenFindById_returnsTheSavedAppointment() {
        CompanyAppointment saved = repo.save(manager(10, 5, 99, List.of(Permission.MANAGE_INVENTORY)));

        CompanyAppointment found = repo.findById(saved.getAppointmentId()).orElseThrow();
        assertEquals(10, found.getCompanyId());
        assertEquals(5, found.getTargetId());
        assertEquals(99, found.getInviterId());
    }

    @Test
    void findById_isEmptyWhenMissing() {
        assertTrue(repo.findById(9999).isEmpty());
    }

    @Test
    void save_roundTripsRoleStatusAndPermissions() {
        CompanyAppointment saved = repo.save(
                manager(10, 5, 99, List.of(Permission.MANAGE_INVENTORY, Permission.VIEW_SALES)));

        CompanyAppointment found = repo.findById(saved.getAppointmentId()).orElseThrow();
        assertEquals(CompanyRole.Manager, found.getRole());
        assertEquals(AppointmentStatus.PENDING, found.getStatus());
        assertEquals(Set.of(Permission.MANAGE_INVENTORY, Permission.VIEW_SALES), found.getPermissions());
    }

    @Test
    void findByTargetId_returnsOnlyThatUsersAppointments() {
        repo.save(manager(10, 5, 99, List.of(Permission.MANAGE_INVENTORY)));
        repo.save(manager(20, 5, 99, List.of(Permission.VIEW_SALES)));
        repo.save(manager(10, 6, 99, List.of(Permission.CONFIGURE_VENUE)));

        assertEquals(2, repo.findByTargetId(5).size());
        assertEquals(1, repo.findByTargetId(6).size());
        assertTrue(repo.findByTargetId(123).isEmpty());
    }

    @Test
    void findByTargetIdAndCompanyId_narrowsToOneCompany() {
        repo.save(manager(10, 5, 99, List.of(Permission.MANAGE_INVENTORY)));
        repo.save(manager(20, 5, 99, List.of(Permission.VIEW_SALES)));

        List<CompanyAppointment> inTen = repo.findByTargetIdAndCompanyId(5, 10);
        assertEquals(1, inTen.size());
        assertEquals(10, inTen.get(0).getCompanyId());
        assertTrue(repo.findByTargetIdAndCompanyId(5, 999).isEmpty());
    }

    @Test
    void findByCompanyId_returnsEveryAppointmentForThatCompany() {
        repo.save(manager(10, 5, 99, List.of(Permission.MANAGE_INVENTORY)));
        repo.save(manager(10, 6, 99, List.of(Permission.VIEW_SALES)));
        repo.save(manager(20, 7, 99, List.of(Permission.CONFIGURE_VENUE)));

        assertEquals(2, repo.findByCompanyId(10).size());
        assertEquals(1, repo.findByCompanyId(20).size());
        assertTrue(repo.findByCompanyId(999).isEmpty());
    }

    @Test
    void save_persistsAnAcceptedLifecycleTransition() {
        CompanyAppointment appt = repo.save(manager(10, 5, 99, List.of(Permission.MANAGE_INVENTORY)));
        appt.accept();            // PENDING -> ACTIVE
        repo.save(appt);          // persist the transition

        CompanyAppointment found = repo.findById(appt.getAppointmentId()).orElseThrow();
        assertEquals(AppointmentStatus.ACTIVE, found.getStatus());
    }

    @Test
    void delete_removesTheAppointment() {
        CompanyAppointment appt = repo.save(manager(10, 5, 99, List.of(Permission.MANAGE_INVENTORY)));
        assertTrue(repo.findById(appt.getAppointmentId()).isPresent());

        repo.delete(appt);
        assertFalse(repo.findById(appt.getAppointmentId()).isPresent());
    }

    @Test
    void nextId_producesDistinctIncreasingValues() {
        int a = repo.nextId();
        int b = repo.nextId();
        assertNotEquals(a, b);
        assertTrue(b > a);
    }
}
