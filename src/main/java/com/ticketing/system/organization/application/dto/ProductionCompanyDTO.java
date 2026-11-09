package com.ticketing.system.organization.application.dto;

// A production company as the registration and company-selection flows return it: the result of
// registerCompany, and the entries of findOwnedCompanies and adminListAllCompanies.
public record ProductionCompanyDTO(
    int companyId,
    String name,
    String description,
    String status,                  // CompanyStatus value as string
    int founderId
) {}
