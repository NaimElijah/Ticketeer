package com.ticketing.system.unit.infrastructure.persistence.CompanyAppointmentPersistence;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.ticketing.system.organization.application.port.out.CompanyAppointmentRepository;
import com.ticketing.system.organization.adapter.out.persistence.JpaCompanyAppointmentRepository;
import com.ticketing.system.organization.adapter.out.persistence.SpringDataCompanyAppointmentRepository;

/**
 * Runs the {@link ICompanyAppointmentRepositoryContractTest} suite against the JPA adapter on an
 * embedded H2 schema. {@code @ActiveProfiles("jpa")} activates {@link JpaCompanyAppointmentRepository};
 * {@code @DataJpaTest} provides H2 + real {@code company_appointments} and {@code appointment_permissions}
 * tables. Each test starts from an empty schema ({@link #cleanTable()}) so the suite is
 * order-independent. This proves the appointment aggregate persists independently now that it is no
 * longer cascaded from {@code User} (task #20). CI never touches a real database.
 */
@DataJpaTest
@ActiveProfiles("jpa")
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
}
