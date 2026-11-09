package com.ticketing.system.ui.presenters.company;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.ticketing.system.shared.dto.CompanyDashboardDTO;
import com.ticketing.system.shared.dto.MyCompanyDTO;
import com.ticketing.system.reporting.application.service.CompanyAnalyticsService;
import com.ticketing.system.organization.application.service.CompanyManagementService;
import com.ticketing.system.shared.exception.InvalidTokenException;
import com.ticketing.system.ui.session.CurrentCompanies;
import com.ticketing.system.ui.support.ServiceErrors;

/**
 * MVP presenter for {@code OwnerDashboardView} (V2-WIRE-OWNER-DASH). Holds no Vaadin imports so
 * the outcome → UI translation lives in the view and the service-call decision tree is
 * unit-testable in isolation (the view reads the token from {@code AuthSession} and passes it in,
 * mirroring {@code ManagerListPresenter}).
 *
 * <p>Resolves the signed-in member's companies (owners + managers, real data) and the live
 * dashboard counters for the selected one. A single {@link #loadFor(String, Integer)} drives both
 * the first render (null companyId → default to the first company) and selector re-selection.
 */
@Component
public class OwnerDashboardPresenter {

    private final CompanyManagementService companyManagementService;
    private final CompanyAnalyticsService companyAnalyticsService;

    /**
     * Creates the presenter.
     *
     * @param companyManagementService lists the companies the member belongs to
     * @param companyAnalyticsService  reads the selected company's dashboard counters
     */
    @Autowired
    public OwnerDashboardPresenter(CompanyManagementService companyManagementService,
                                   CompanyAnalyticsService companyAnalyticsService) {
        this.companyManagementService = companyManagementService;
        this.companyAnalyticsService = companyAnalyticsService;
    }

    /**
     * Loads the member's companies plus the selected company's stats. When {@code companyId} is
     * null (first load) or unknown, the first company is selected.
     *
     * @param token     the member's token; {@code null} or a rejected token gives
     *     {@link Outcome.NotAuthenticated}
     * @param companyId the company to show, or {@code null} for the first
     * @return {@link Outcome.NoCompany} when the member belongs to no company; {@link Outcome.Failure}
     *     with {@code ServiceErrors}' text for any other failure
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
            CompanyDashboardDTO stats = companyAnalyticsService.dashboard(token, selected.companyId());
            return new Outcome.Success(companies, selected, stats);
        } catch (InvalidTokenException e) {
            return new Outcome.NotAuthenticated();
        } catch (RuntimeException e) {
            return new Outcome.Failure(ServiceErrors.userMessage(e));
        }
    }

    /**
     * Makes {@code companyId} the company selected in the workspace ({@link CurrentCompanies}), the one
     * the manager screens and the capability gate act on. The dashboard's Company selector is where an
     * owner of several companies picks one. Whether the caller may act on it stays the service's check.
     *
     * @param companyId the company the selector chose
     */
    public void selectCompany(int companyId) {
        CurrentCompanies.setCurrentCompany(companyId);
    }

    /** Sealed outcome the view switches on to render the dashboard or an empty state. */
    public sealed interface Outcome {
        /**
         * The dashboard loaded.
         *
         * @param companies the member's companies, managers' included
         * @param selected  the company the counters belong to; one of {@code companies}
         * @param stats     the selected company's dashboard counters
         */
        record Success(List<MyCompanyDTO> companies, MyCompanyDTO selected,
                       CompanyDashboardDTO stats) implements Outcome { }

        /** There is no token, or the service rejected it as invalid. */
        record NotAuthenticated() implements Outcome { }

        /** The member belongs to no company. */
        record NoCompany() implements Outcome { }

        /**
         * The load failed.
         *
         * @param reason the text the user may read, from {@code ServiceErrors.userMessage}
         */
        record Failure(String reason) implements Outcome { }
    }
}
