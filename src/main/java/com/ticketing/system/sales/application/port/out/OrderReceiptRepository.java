package com.ticketing.system.sales.application.port.out;
import com.ticketing.system.sales.domain.OrderReceipt;

import java.util.List;
import java.util.Optional;

import com.ticketing.system.Core.Application.dto.GlobalHistoryFiltersDTO;
import com.ticketing.system.shared.IRepository;

// Aggregate-root entry point for the OrderReceipt aggregate.
public interface OrderReceiptRepository extends IRepository<OrderReceipt, Integer> {

    int nextId();
    
    void save(OrderReceipt orderReceipt);

    Optional<OrderReceipt> findByOrderReceiptId(int orderReceiptId);

    // UC-16 — member's own purchase history.
    List<OrderReceipt> findByHolderUserId(int holderUserId);

    List<OrderReceipt> findByEventIds(List<Integer> eventIds);

    // UC-22 — company-scoped sales (filtered down to the company's events).
    // Because this method(findByCompanyId) is conceptually in the wrong place. A receipt does not know company ownership directly.
    // @Deprecated
    // List<OrderReceipt> findByCompanyId(int companyId);

    // UC-31 — global view with filters (admin only).
    List<OrderReceipt> findGlobal(GlobalHistoryFiltersDTO filters);

    List<OrderReceipt> findByEventId(int eventId);
}
