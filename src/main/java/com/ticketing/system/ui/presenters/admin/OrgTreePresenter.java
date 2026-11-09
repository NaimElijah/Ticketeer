package com.ticketing.system.ui.presenters.admin;

import com.ticketing.system.organization.application.dto.OrganizationalTreeNodeDTO;
import com.ticketing.system.organization.application.dto.ProductionCompanyDTO;
import com.ticketing.system.organization.application.service.CompanyManagementService;
import com.ticketing.system.shared.exception.InvalidTokenException;
import com.ticketing.system.ui.support.ServiceErrors;
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

    /** Creates the presenter over the company management service. */
    @Autowired
    public OrgTreePresenter(CompanyManagementService companyManagementService) {
        this.companyManagementService = companyManagementService;
    }

    /**
     * Loads the organizational tree for the selected company.
     * Admins see all companies; owners see only their own. When {@code companyId}
     * is null (first load) or not in the list, the first company is used.
     *
     * @param token     the signed-in caller's token; {@code null} gives {@link Outcome.NotAuthenticated}
     * @param companyId the company to show, or {@code null} for the first
     * @param isAdmin   {@code true} to list and read as an admin, {@code false} as an owner
     * @return {@link Outcome.NoCompany} when the caller has no company to list; {@link Outcome.Failure}
     *     with {@code ServiceErrors}' text for any other failure
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
            return new Outcome.Failure(ServiceErrors.userMessage(e));
        }
    }

    /** Sealed outcome the view switches on. */
    public sealed interface Outcome {
        /**
         * The tree loaded.
         *
         * @param companies the companies the caller may pick from
         * @param selected  the company whose tree is shown; one of {@code companies}
         * @param tree      the selected company's organizational tree
         */
        record Success(List<ProductionCompanyDTO> companies,
                       ProductionCompanyDTO selected,
                       OrganizationalTreeNodeDTO tree) implements Outcome { }

        /** There is no token, or the service rejected it as invalid. */
        record NotAuthenticated() implements Outcome { }

        /** The caller has no company to show a tree for. */
        record NoCompany() implements Outcome { }

        /**
         * The load failed.
         *
         * @param reason the text the user may read, from {@code ServiceErrors.userMessage}
         */
        record Failure(String reason) implements Outcome { }
    }
}
