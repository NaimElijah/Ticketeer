package com.ticketing.system.shared.exception;

// Specific subclass of EntityNotFoundException for ProductionCompany lookups.
// UC-18, UC-19, UC-22, UC-25.
public class CompanyNotFoundException extends EntityNotFoundException {

    // Id-free message for user-facing paths, so the id does not leak to the UI (avoids exposing
    // internal identifiers / enabling enumeration). A throw site that wants the id in the server log
    // writes it there itself.
    public CompanyNotFoundException() {
        super("Company not found");
    }

    public CompanyNotFoundException(Object companyId) {
        super("ProductionCompany", companyId);
    }
}
