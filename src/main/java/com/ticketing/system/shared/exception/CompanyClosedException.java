package com.ticketing.system.shared.exception;

/**
 * Thrown when an operation names a company whose status is not {@code ACTIVE}.
 *
 * <p>Its only throw site is {@code CatalogService.requireActiveCompany}, reached by the
 * company-scoped event search (UC-3). Other catalog reads refuse a closed company's event as
 * {@link EventNotFoundException}. II.4.13.x (company closure, cancelled in v0 scope). The message
 * reaches the user.
 */
public class CompanyClosedException extends DomainException {

    /**
     * Creates the exception.
     *
     * @param companyId the company whose status is not {@code ACTIVE}
     */
    public CompanyClosedException(int companyId) {
        super("Company " + companyId + " is closed");
    }
}
