/**
 * Organization bounded context.
 *
 * <p>Owns production companies and their governance: the {@code ProductionCompany} aggregate, company
 * status and policies, and the company-management, analytics, and ratings use cases. Hexagonal layout:
 * {@code domain}, {@code application.port.out} (the {@code ProductionCompanyRepository} driven port),
 * {@code application.service}, {@code adapter.out.persistence}.
 *
 * <p>Migration note: company appointments (the user/company membership relationship) remain owned by
 * the identity {@code User} aggregate for now &mdash; promoting {@code CompanyAppointment} to an
 * organization aggregate is a dedicated later step. This context still calls into identity and other
 * not-yet-migrated contexts directly; module-boundary verification is switched on at Step 10.
 */
package com.ticketing.system.organization;
