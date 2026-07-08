package com.ticketing.system.unit.infrastructure.persistence.UserPersistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.shared.exception.UserNotFoundException;
import com.ticketing.system.Core.Domain.users.AppointmentStatus;
import com.ticketing.system.Core.Domain.users.CompanyAppointment;
import com.ticketing.system.Core.Domain.users.CompanyRole;
import com.ticketing.system.identity.application.port.out.UserRepository;
import com.ticketing.system.Core.Domain.users.Permission;
import com.ticketing.system.identity.domain.User;

// Contract tests every UserRepository implementation must satisfy. Future JPA-backed
// adapter will subclass this with its own newRepository() factory; tests are reused.
abstract class IUserRepositoryContractTest {

    protected abstract UserRepository newRepository();

    private UserRepository repo;

    @BeforeEach
    void setUp() {
        repo = newRepository();
    }

    @Test
    void save_thenFindByUsername_returnsTheSavedUser() {
        int id = repo.nextId();
        User user = new User(id, "alice", "alice@example.com", "HASH",20);
        repo.save(user);

        Optional<User> found = repo.findByUsername("alice");
        assertTrue(found.isPresent());
        assertEquals(id, found.get().getUserId());
        assertEquals("alice@example.com", found.get().getEmail());
    }

    @Test
    void save_thenFindByEmail_returnsTheSavedUser() {
        int id = repo.nextId();
        User user = new User(id, "alice", "alice@example.com", "HASH",2);
        repo.save(user);

        Optional<User> found = repo.findByEmail("alice@example.com");
        assertTrue(found.isPresent());
        assertEquals("alice", found.get().getUsername());
    }

    @Test
    void findByUsername_returnsEmptyWhenMissing() {
        assertFalse(repo.findByUsername("ghost").isPresent());
    }

    @Test
    void findByEmail_returnsEmptyWhenMissing() {
        assertFalse(repo.findByEmail("ghost@example.com").isPresent());
    }

    @Test
    void existsByUsername_falseWhenMissingTrueWhenSaved() {
        assertFalse(repo.existsByUsername("alice"));
        repo.save(new User(repo.nextId(), "alice", "alice@example.com", "HASH",15));
        assertTrue(repo.existsByUsername("alice"));
    }

    @Test
    void nextId_producesDistinctIncreasingValues() {
        int a = repo.nextId();
        int b = repo.nextId();
        int c = repo.nextId();
        assertNotEquals(a, b);
        assertNotEquals(b, c);
        assertTrue(b > a);
        assertTrue(c > b);
    }

    @Test
    void getUserById_returnsTheSavedUser() {
        int id = repo.nextId();
        User user = new User(id, "alice", "alice@example.com", "HASH",15);
        repo.save(user);

        User found = repo.getUserById(id);
        assertEquals("alice", found.getUsername());
    }

    @Test
    void getUserById_throwsWhenMissing() {
        assertThrows(UserNotFoundException.class, () -> repo.getUserById(9999));
    }

    @Test
    void delete_removesTheUser() {
        int id = repo.nextId();
        repo.save(new User(id, "alice", "alice@example.com", "HASH",15));
        assertTrue(repo.existsByUsername("alice"));

        repo.delete(id);
        assertFalse(repo.existsByUsername("alice"));
    }

    @Test
    void save_persistsCompanyAppointmentsAndPermissions() {
        int id = repo.nextId();
        User user = new User(id, "bob", "bob@example.com", "HASH", 30);
        user.receiveManagerAppointment(10, 99, List.of(Permission.MANAGE_INVENTORY, Permission.VIEW_SALES));
        repo.save(user);

        User found = repo.getUserById(id);
        List<CompanyAppointment> appointments = found.getAllCompanyAppointments();
        assertEquals(1, appointments.size());
        CompanyAppointment appointment = appointments.get(0);
        assertEquals(10, appointment.getCompanyId());
        assertEquals(CompanyRole.Manager, appointment.getRole());
        assertEquals(AppointmentStatus.PENDING, appointment.getStatus());
        assertEquals(Set.of(Permission.MANAGE_INVENTORY, Permission.VIEW_SALES), appointment.getPermissions());
    }

    @Test
    void findUsersWithPendingAppointmentForCompany_returnsOnlyUsersWithAPendingAppointmentThere() {
        int withPending = repo.nextId();
        User u1 = new User(withPending, "hasPending", "hp@example.com", "HASH", 30);
        u1.receiveManagerAppointment(10, 99, List.of(Permission.VIEW_SALES));
        repo.save(u1);

        int withoutPending = repo.nextId();
        repo.save(new User(withoutPending, "noPending", "np@example.com", "HASH", 30));

        List<User> pending = repo.findUsersWithPendingAppointmentForCompany(10);
        assertEquals(1, pending.size());
        assertEquals(withPending, pending.get(0).getUserId());
        assertTrue(repo.findUsersWithPendingAppointmentForCompany(77).isEmpty());
    }
}
