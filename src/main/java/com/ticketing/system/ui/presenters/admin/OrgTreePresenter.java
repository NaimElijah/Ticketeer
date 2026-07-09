package com.ticketing.system.ui.presenters.admin;

import com.ticketing.system.organization.application.dto.OrganizationalTreeNodeDTO;
import com.ticketing.system.organization.application.dto.ProductionCompanyDTO;
import com.ticketing.system.organization.application.service.CompanyManagementService;
import com.ticketing.system.shared.exception.InvalidTokenException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * MVP presenter for {@code OrganizationalTreeView} (UC-25 / V2-VIEW-03). Vaadin-free
 * POJO: resolves the signed-in owner's companies, selects the active one, and fetches
 * its org tree via {@link CompanyManagementService#viewOrganizationalTree}.
 */
@Component
public class OrgTreePresenter {

    private final CompanyManagementService companyManagementService;

    @Autowired
    public OrgTreePresenter(CompanyManagementService companyManagementService) {
        this.companyManagementService = companyManagementService;
    }

    /**
     * Loads the organizational tree for the selected company.
     * Admins see all companies; owners see only their own. When {@code companyId}
     * is null (first load) or not in the list, the first company is used.
     */
    public Outcome load(String token, Integer companyId, boolean isAdmin) {
        if (token == null) {
            return new Outcome.NotAuthenticated();
        }
        try {
            List<ProductionCompanyDTO> companies = isAdmin
                    ? companyManagementService.adminListAllCompanies(token)
                    : companyManagementService.findOwnedCompanies(token);
            if (companies.isEmpty()) {
                return new Outcome.NoCompany();
            }
            ProductionCompanyDTO selected = companies.stream()
                    .filter(c -> companyId != null && c.companyId() == companyId)
                    .findFirst()
                    .orElse(companies.get(0));
            OrganizationalTreeNodeDTO tree = isAdmin
                    ? companyManagementService.adminViewOrgTree(token, selected.companyId())
                    : companyManagementService.viewOrganizationalTree(token, selected.companyId());
            return new Outcome.Success(companies, selected, tree);
        } catch (InvalidTokenException e) {
            return new Outcome.NotAuthenticated();
        } catch (RuntimeException e) {
            return new Outcome.Failure(e.getMessage());
        }
    }

    /** Sealed outcome the view switches on. */
    public sealed interface Outcome {
        record Success(List<ProductionCompanyDTO> companies,
                       ProductionCompanyDTO selected,
                       OrganizationalTreeNodeDTO tree) implements Outcome { }
        record NotAuthenticated() implements Outcome { }
        record NoCompany() implements Outcome { }
        record Failure(String reason) implements Outcome { }
    }
}
