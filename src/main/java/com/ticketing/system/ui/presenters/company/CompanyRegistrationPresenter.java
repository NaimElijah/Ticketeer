package com.ticketing.system.ui.presenters.company;

import org.springframework.stereotype.Component;

import com.ticketing.system.Core.Application.dto.CompanyRegistrationDTO;
import com.ticketing.system.Core.Application.dto.ProductionCompanyDTO;
import com.ticketing.system.organization.application.service.CompanyManagementService;
import com.ticketing.system.ui.session.AuthSession;
import com.ticketing.system.ui.session.CurrentCompanies;

@Component
public class CompanyRegistrationPresenter {

    private final CompanyManagementService companyManagementService;

    public CompanyRegistrationPresenter(CompanyManagementService companyManagementService) {
        this.companyManagementService = companyManagementService;
    }

    public Outcome register(String name, String description) {
        String token = AuthSession.token();
        if (token == null || token.isBlank()) {
            return new Outcome.NotAuthenticated();
        }

        try {
            ProductionCompanyDTO company = companyManagementService.registerCompany(
                    token,
                    new CompanyRegistrationDTO(name, description));
            CurrentCompanies.setCurrentCompany(company.companyId());
            return new Outcome.Success(company);
        } catch (IllegalArgumentException e) {
            return new Outcome.InvalidInput(e.getMessage());
        } catch (IllegalStateException e) {
            return new Outcome.NameTaken(e.getMessage());
        } catch (RuntimeException e) {
            return new Outcome.Failure(e.getMessage());
        }
    }

    public sealed interface Outcome {
        record Success(ProductionCompanyDTO company) implements Outcome { }

        record NotAuthenticated() implements Outcome { }

        record InvalidInput(String reason) implements Outcome { }

        record NameTaken(String reason) implements Outcome { }

        record Failure(String reason) implements Outcome { }
    }
}
