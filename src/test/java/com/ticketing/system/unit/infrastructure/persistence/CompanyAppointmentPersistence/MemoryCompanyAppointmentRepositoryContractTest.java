package com.ticketing.system.unit.infrastructure.persistence.CompanyAppointmentPersistence;

import com.ticketing.system.organization.application.port.out.CompanyAppointmentRepository;
import com.ticketing.system.organization.adapter.out.persistence.MemoryCompanyAppointmentRepository;

/** Runs the {@link ICompanyAppointmentRepositoryContractTest} suite against the in-memory adapter. */
class MemoryCompanyAppointmentRepositoryContractTest extends ICompanyAppointmentRepositoryContractTest {

    @Override
    protected CompanyAppointmentRepository newRepository() {
        return new MemoryCompanyAppointmentRepository();
    }
}
