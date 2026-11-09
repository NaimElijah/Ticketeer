package com.ticketing.system.shared.exception;

/**
 * The {@link EntityNotFoundException} for a {@code ProductionCompany} lookup, thrown by the company
 * repositories and by the company and event services. UC-18, UC-19, UC-22, UC-25.
 */
public class CompanyNotFoundException extends EntityNotFoundException {

    /**
     * Creates the exception with an id-free message, for user-facing paths, so the id does not leak to
     * the UI (avoids exposing internal identifiers and enabling enumeration). A throw site that wants the
     * id in the server log writes it there itself.
     */
    public CompanyNotFoundException() {
        super("Company not found");
    }

    /**
     * Creates the exception for a company id that matched no company.
     *
     * @param companyId the id that was looked up
     */
    public CompanyNotFoundException(Object companyId) {
        super("ProductionCompany", companyId);
    }
}
