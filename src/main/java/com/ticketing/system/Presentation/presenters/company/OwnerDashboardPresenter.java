package com.ticketing.system.Presentation.presenters.company;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.ticketing.system.Core.Application.dto.CompanyDashboardDTO;
import com.ticketing.system.Core.Application.dto.MyCompanyDTO;
import com.ticketing.system.organization.application.service.CompanyAnalyticsService;
import com.ticketing.system.organization.application.service.CompanyManagementService;
import com.ticketing.system.shared.exception.InvalidTokenException;

/**
 * MVP presenter for {@code OwnerDashboardView} (V2-WIRE-OWNER-DASH). Holds no Vaadin imports so
 * the outcome → UI translation lives in the view and the service-call decision tree is
 * unit-testable in isolation (the view reads the token from {@code AuthSession} and passes it in,
 * mirroring {@code ManagerListPresenter}).
 *
 * <p>Resolves the signed-in member's companies (owners + managers, real data) and the live
 * dashboard counters for the selected one. A single {@link #loadFor(String, Integer)} drives both
 * the first render (null companyId → default to the first company) and selector re-selection.
 * Per-company capability/persona re-resolution is still owned by the mock resolver (#256), so the
 * selector only re-renders stats + subtitle.
 */
@Component
public class OwnerDashboardPresenter {

    private final CompanyManagementService companyManagementService;
    private final CompanyAnalyticsService companyAnalyticsService;

    @Autowired
    public OwnerDashboardPresenter(CompanyManagementService companyManagementService,
                                   CompanyAnalyticsService companyAnalyticsService) {
        this.companyManagementService = companyManagementService;
        this.companyAnalyticsService = companyAnalyticsService;
    }

    /**
     * Loads the member's companies plus the selected company's stats. When {@code companyId} is
     * null (first load) or unknown, the first company is selected.
     */
    public Outcome loadFor(String token, Integer companyId) {
        if (token == null) {
            return new Outcome.NotAuthenticated();
        }
        try {
            List<MyCompanyDTO> companies = companyManagementService.findMyCompanies(token);
            if (companies.isEmpty()) {
                return new Outcome.NoCompany();
            }
            MyCompanyDTO selected = companies.stream()
                .filter(c -> companyId != null && c.companyId() == companyId)
                .findFirst()
                .orElse(companies.get(0));
            CompanyDashboardDTO stats = companyAnalyticsService.dashboard(selected.companyId());
            return new Outcome.Success(companies, selected, stats);
        } catch (InvalidTokenException e) {
            return new Outcome.NotAuthenticated();
        } catch (RuntimeException e) {
            return new Outcome.Failure(e.getMessage());
        }
    }

    /** Sealed outcome the view switches on to render the dashboard or an empty state. */
    public sealed interface Outcome {
        record Success(List<MyCompanyDTO> companies, MyCompanyDTO selected,
                       CompanyDashboardDTO stats) implements Outcome { }
        record NotAuthenticated() implements Outcome { }
        record NoCompany() implements Outcome { }
        record Failure(String reason) implements Outcome { }
    }
}
