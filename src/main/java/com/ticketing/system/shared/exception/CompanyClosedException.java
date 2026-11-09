package com.ticketing.system.shared.exception;

/**
 * Thrown when an operation names a company whose status is not {@code ACTIVE}.
 *
 * <p>Its only throw site is {@code CatalogService.requireActiveCompany}, reached only by
 * {@code searchByCompany} (UC-3), which no production code calls today. Other catalog reads refuse a
 * closed company's event as {@link EventNotFoundException}. II.4.13.x (company closure, cancelled in
 * v0 scope).
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
