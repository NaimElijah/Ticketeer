package com.ticketing.system.organization.application.dto;

/**
 * A production company as the registration and company-selection flows return it: the result of
 * {@code registerCompany}, and the entries of {@code findOwnedCompanies} and {@code adminListAllCompanies}.
 *
 * @param status    the name of the company's {@code CompanyStatus}
 * @param founderId the user id of the company's founder
 */
public record ProductionCompanyDTO(
    int companyId,
    String name,
    String description,
    String status,
    int founderId
) {}
