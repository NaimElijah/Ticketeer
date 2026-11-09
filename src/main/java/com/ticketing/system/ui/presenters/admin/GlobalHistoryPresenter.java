package com.ticketing.system.ui.presenters.admin;

import java.util.List;

import org.springframework.stereotype.Component;

import com.ticketing.system.shared.dto.GlobalHistoryFiltersDTO;
import com.ticketing.system.sales.application.dto.PurchaseHistoryDTO.PurchaseRecordDTO;
import com.ticketing.system.reporting.application.dto.SalesSummaryDTO;
import com.ticketing.system.reporting.application.service.SalesSummaryCalculator;
import com.ticketing.system.governance.application.service.SystemAdminService;
import com.ticketing.system.ui.presenters.ListOutcome;
import com.ticketing.system.ui.session.AuthSession;
import com.ticketing.system.ui.support.ServiceErrors;

/**
 * Read-only presenter for {@code GlobalHistoryView}. Loads every purchase
 * record across the platform via {@link SystemAdminService#viewGlobalHistory}
 * (admin-gated). Records are already enriched with buyer / event / company
 * names by {@code OrderReceiptMapper}, so the view filters them client-side.
 *
 * <p>Passes an all-null {@link GlobalHistoryFiltersDTO} (every field nullable =
 * no server filter). A caller without a token gets no records; a failed read is a
 * {@link ListOutcome.Failure}, never an empty history.
 */
@Component
public class GlobalHistoryPresenter {

    private final SystemAdminService systemAdminService;
    private final SalesSummaryCalculator salesSummaryCalculator;

    /** Creates the presenter over the admin history service and the calculator behind {@link #summarize}. */
    public GlobalHistoryPresenter(SystemAdminService systemAdminService,
                                  SalesSummaryCalculator salesSummaryCalculator) {
        this.systemAdminService = systemAdminService;
        this.salesSummaryCalculator = salesSummaryCalculator;
    }

    /** Headline figures over the records the view currently shows (after its filters). */
    public SalesSummaryDTO summarize(List<PurchaseRecordDTO> records) {
        return salesSummaryCalculator.summarize(records);
    }

    /**
     * Every purchase record across the platform.
     *
     * @return the records, none without a token; a {@link ListOutcome.Failure} with
     *         {@code ServiceErrors}' text when the read fails, an admin refusal included
     */
    public ListOutcome<PurchaseRecordDTO> loadAllRecords() {
        String token = AuthSession.token();
        if (token == null) {
            return new ListOutcome.Loaded<>(List.of());
        }
        try {
            GlobalHistoryFiltersDTO noFilter = new GlobalHistoryFiltersDTO(null, null, null, null, null);
            return new ListOutcome.Loaded<>(systemAdminService.viewGlobalHistory(token, noFilter).stream()
                    .flatMap(h -> h.records().stream())
                    .toList());
        } catch (RuntimeException e) {
            return new ListOutcome.Failure<>(ServiceErrors.userMessageLogged(e, "load global purchase history"));
        }
    }
}
