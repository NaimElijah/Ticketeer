package com.ticketing.system.ui.presenters.company;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.ticketing.system.shared.dto.MyCompanyDTO;
import com.ticketing.system.sales.application.dto.PurchaseHistoryDTO;
import com.ticketing.system.sales.application.dto.PurchaseHistoryDTO.PurchaseRecordDTO;
import com.ticketing.system.reporting.application.dto.SalesSummaryDTO;
import com.ticketing.system.reporting.application.service.CompanyAnalyticsService;
import com.ticketing.system.reporting.application.service.SalesSummaryCalculator;
import com.ticketing.system.organization.application.service.CompanyManagementService;
import com.ticketing.system.shared.exception.InvalidTokenException;
import com.ticketing.system.ui.support.ServiceErrors;

/**
 * Presenter for {@code CompanySalesView}: loads the sales history of the caller's company and
 * summarizes the records the view shows. Holds no Vaadin imports; it takes the member token the view
 * reads from its session and returns a typed outcome.
 *
 * <p>The company is the one the workspace has selected when it is one of the caller's companies, else
 * the first of them. Managers count, since a manager may hold {@code VIEW_SALES}.
 */
@Component
public class CompanySalesPresenter {

    private final CompanyManagementService companyManagementService;
    private final CompanyAnalyticsService companyAnalyticsService;
    private final SalesSummaryCalculator salesSummaryCalculator;

    /**
     * Creates the presenter.
     *
     * @param companyManagementService resolves which companies the caller belongs to
     * @param companyAnalyticsService  reads the company's sales history
     * @param salesSummaryCalculator   computes the headline figures for {@link #summarize}
     */
    @Autowired
    public CompanySalesPresenter(CompanyManagementService companyManagementService,
                                  CompanyAnalyticsService companyAnalyticsService,
                                  SalesSummaryCalculator salesSummaryCalculator) {
        this.companyManagementService = companyManagementService;
        this.companyAnalyticsService = companyAnalyticsService;
        this.salesSummaryCalculator = salesSummaryCalculator;
    }

    /** Headline figures over the records the view currently shows (after its date filter). */
    public SalesSummaryDTO summarize(List<PurchaseRecordDTO> records) {
        return salesSummaryCalculator.summarize(records);
    }

    /**
     * Loads the sales history of one of the caller's companies.
     *
     * @param token     the caller's member token; {@code null} gives {@link Outcome.NotAuthenticated}
     * @param companyId the company the workspace has selected, or {@code null}; used when it is one of
     *     the caller's companies, else the first of them is used
     * @return {@link Outcome.NoCompany} when the caller belongs to no company; {@link Outcome.Failure}
     *     with {@code ServiceErrors}' text for any other failure
     */
    public Outcome load(String token, Integer companyId) {
        if (token == null) return new Outcome.NotAuthenticated();
        try {
            // findMyCompanies (manager-inclusive) so a manager granted VIEW_SALES sees their
            // company's sales — not just owners; honor the workspace's selected company, else first.
            List<MyCompanyDTO> companies = companyManagementService.findMyCompanies(token);
            if (companies.isEmpty()) return new Outcome.NoCompany();
            MyCompanyDTO selected = companies.stream()
                    .filter(c -> companyId != null && c.companyId() == companyId)
                    .findFirst()
                    .orElse(companies.get(0));
            // The view derives all headline figures from the order records (refund-aware, honoring
            // the date filter), so the fixed 30-day dashboard() snapshot is no longer fetched here.
            PurchaseHistoryDTO sales = companyAnalyticsService.salesHistory(token, selected.companyId());
            return new Outcome.Success(selected.name(), sales);
        } catch (InvalidTokenException e) {
            return new Outcome.NotAuthenticated();
        } catch (RuntimeException e) {
            return new Outcome.Failure(ServiceErrors.userMessage(e));
        }
    }

    /** Sealed outcome of {@link #load}, which the view switches on. */
    public sealed interface Outcome {
        /**
         * The sales history loaded.
         *
         * @param companyName the name of the company the history belongs to
         * @param sales       the company's purchase records, possibly none
         */
        record Success(String companyName, PurchaseHistoryDTO sales) implements Outcome {}

        /** There is no token, or the service rejected it as invalid. */
        record NotAuthenticated() implements Outcome {}

        /** The caller belongs to no company. */
        record NoCompany() implements Outcome {}

        /**
         * The load failed.
         *
         * @param reason the text the user may read, from {@code ServiceErrors.userMessage}
         */
        record Failure(String reason) implements Outcome {}
    }
}
