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

@Component
public class CompanySalesPresenter {

    private final CompanyManagementService companyManagementService;
    private final CompanyAnalyticsService companyAnalyticsService;
    private final SalesSummaryCalculator salesSummaryCalculator;

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

    public sealed interface Outcome {
        record Success(String companyName, PurchaseHistoryDTO sales) implements Outcome {}
        record NotAuthenticated() implements Outcome {}
        record NoCompany() implements Outcome {}
        record Failure(String reason) implements Outcome {}
    }
}
