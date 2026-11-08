package com.ticketing.system.unit.infrastructure.persistence.CompanyAppointmentPersistence;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import com.ticketing.system.organization.application.port.out.CompanyAppointmentRepository;
import com.ticketing.system.organization.adapter.out.persistence.JpaCompanyAppointmentRepository;
import com.ticketing.system.organization.adapter.out.persistence.SpringDataCompanyAppointmentRepository;
import com.ticketing.system.organization.domain.CompanyAppointment;
import com.ticketing.system.organization.domain.Permission;

/**
 * Runs the {@link ICompanyAppointmentRepositoryContractTest} suite against the JPA adapter on an
 * embedded H2 schema. {@code @Import} adds {@link JpaCompanyAppointmentRepository};
 * {@code @DataJpaTest} provides H2 + real {@code company_appointments} and {@code appointment_permissions}
 * tables. Each test starts from an empty schema ({@link #cleanTable()}) so the suite is
 * order-independent. This proves the appointment aggregate persists independently now that it is no
 * longer cascaded from {@code User} (task #20). CI never touches a real database.
 */
@DataJpaTest
@Import(JpaCompanyAppointmentRepository.class)
class JpaCompanyAppointmentRepositoryContractTest extends ICompanyAppointmentRepositoryContractTest {

    @Autowired
    private JpaCompanyAppointmentRepository repository;

    @Autowired
    private SpringDataCompanyAppointmentRepository data;

    @BeforeEach
    void cleanTable() {
        data.deleteAll();
    }

    @Override
    protected CompanyAppointmentRepository newRepository() {
        return repository;
    }

    // A new adapter instance models a restarted process (#19, as JpaTicketRepositoryContractTest does for
    // tickets): the id sequence must resume above the rows a previous boot persisted, not start at 1.
    @Test
    void GivenAppointmentsPersistedByAPreviousBoot_WhenAFreshAdapterMintsAnId_ThenItIsAboveTheExistingMax() {
        data.saveAllAndFlush(List.of(appointment(1), appointment(2), appointment(3)));

        int id = new JpaCompanyAppointmentRepository(data).nextId();

        assertTrue(id > 3, "appointment id after a restart: " + id);
    }

    private static CompanyAppointment appointment(int id) {
        return CompanyAppointment.ManagerAppointment(id, 10, 5, 99, List.of(Permission.VIEW_SALES),
                LocalDateTime.of(2027, 7, 15, 17, 0));
    }
}
