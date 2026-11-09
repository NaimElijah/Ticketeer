/**
 * Organization bounded context.
 *
 * <p>Owns production companies and company membership: the {@code ProductionCompany} aggregate (status,
 * purchase policy, owner and manager lists) and the {@code CompanyAppointment} aggregate (the
 * appointments that give a member a role and permissions in a company), with the company-management
 * and membership use cases. Hexagonal layout: {@code domain}, {@code application.port.out} (the
 * {@code ProductionCompanyRepository} and {@code CompanyAppointmentRepository} driven ports, and
 * {@code CompanyEventStatsPort}, which catalog implements), {@code application.service},
 * {@code adapter.out.persistence}.
 *
 * <p>Company analytics live in {@code reporting} and company ratings in {@code catalog}. This context
 * still calls into identity directly, for session tokens and users.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Organization", type = org.springframework.modulith.ApplicationModule.Type.OPEN)
package com.ticketing.system.organization;
