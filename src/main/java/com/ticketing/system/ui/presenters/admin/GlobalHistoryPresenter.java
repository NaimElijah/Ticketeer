package com.ticketing.system.ui.presenters.admin;

import java.util.List;

import org.springframework.stereotype.Component;

import com.ticketing.system.shared.dto.GlobalHistoryFiltersDTO;
import com.ticketing.system.sales.application.dto.PurchaseHistoryDTO.PurchaseRecordDTO;
import com.ticketing.system.governance.application.service.SystemAdminService;
import com.ticketing.system.ui.session.AuthSession;

import lombok.extern.slf4j.Slf4j;

/**
 * Read-only presenter for {@code GlobalHistoryView}. Loads every purchase
 * record across the platform via {@link SystemAdminService#viewGlobalHistory}
 * (admin-gated). Records are already enriched with buyer / event / company
 * names by {@code OrderReceiptMapper}, so the view filters them client-side.
 *
 * <p>Passes an all-null {@link GlobalHistoryFiltersDTO} (every field nullable =
 * no server filter). Degrades to an empty list when the caller is not an admin.
 */
@Slf4j
@Component
public class GlobalHistoryPresenter {

    private final SystemAdminService systemAdminService;

    public GlobalHistoryPresenter(SystemAdminService systemAdminService) {
        this.systemAdminService = systemAdminService;
    }

    /** Every purchase record across the platform; empty when not an admin / on failure. */
    public List<PurchaseRecordDTO> loadAllRecords() {
        String token = AuthSession.token();
        if (token == null) {
            return List.of();
        }
        try {
            GlobalHistoryFiltersDTO noFilter = new GlobalHistoryFiltersDTO(null, null, null, null, null);
            return systemAdminService.viewGlobalHistory(token, noFilter).stream()
                    .flatMap(h -> h.records().stream())
                    .toList();
        } catch (RuntimeException e) {
            log.warn("Failed to load global purchase history: {}", e.getMessage());
            return List.of();
        }
    }
}
