package com.ticketing.system.shared.exception;

/**
 * Reserved for a company registration whose name is already taken. UC-18.
 *
 * <p>No production code throws it today: {@code CompanyManagementService.registerCompany} refuses a
 * taken name with an {@link IllegalStateException} carrying {@code COMPANY_NAME_TAKEN_MESSAGE}.
 */
public class DuplicateCompanyException extends DomainException {

    public DuplicateCompanyException(String name) {
        super("Company name already registered: " + name);
    }
}
