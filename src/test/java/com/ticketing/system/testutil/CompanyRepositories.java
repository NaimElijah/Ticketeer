package com.ticketing.system.testutil;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ticketing.system.organization.application.port.out.ProductionCompanyRepository;
import com.ticketing.system.organization.domain.CompanyStatus;
import com.ticketing.system.organization.domain.ProductionCompany;

/** Company repository doubles for tests that need a company to exist, not to be a particular one. */
public final class CompanyRepositories {

    private CompanyRepositories() { }

    /**
     * A stand-in for the organization port that answers every company id with the same active company,
     * which has no purchase policy of its own. Its id is not the one asked for: the paths these tests drive
     * read only the policy. A test that needs a policy stubs that company id itself.
     *
     * @return a mock of the port, holding one real company
     */
    public static ProductionCompanyRepository everyCompanyWithoutAPolicy() {
        ProductionCompanyRepository companies = mock(ProductionCompanyRepository.class);
        ProductionCompany anyCompany = new ProductionCompany(1, 1, "Any company", CompanyStatus.ACTIVE, null, null);
        when(companies.getCompanyById(anyInt())).thenReturn(anyCompany);
        return companies;
    }
}
