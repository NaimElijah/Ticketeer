package com.ticketing.system.shared.exception;

// Specific subclass of EntityNotFoundException for ProductionCompany lookups.
// UC-18, UC-19, UC-22, UC-25.
public class CompanyNotFoundException extends EntityNotFoundException {

    // Id-free message for user-facing paths — the id is written to the server log at the throw site,
    // not leaked to the UI (avoids exposing internal identifiers / enabling enumeration).
    public CompanyNotFoundException() {
        super("Company not found");
    }

    public CompanyNotFoundException(Object companyId) {
        super("ProductionCompany", companyId);
    }
}
