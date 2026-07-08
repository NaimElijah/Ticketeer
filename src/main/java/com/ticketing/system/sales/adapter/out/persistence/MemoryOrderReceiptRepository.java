package com.ticketing.system.sales.adapter.out.persistence;
import com.ticketing.system.identity.application.service.MemberAccountService;
import com.ticketing.system.governance.application.service.SystemAdminService;
import com.ticketing.system.sales.application.service.CheckoutService;
import com.ticketing.system.identity.domain.Admin;

import com.ticketing.system.shared.persistence.RepositoryLocks;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import com.ticketing.system.shared.dto.GlobalHistoryFiltersDTO;
import com.ticketing.system.sales.application.port.out.OrderReceiptRepository;
import com.ticketing.system.sales.domain.OrderReceipt;

/**
 * In-memory {@link OrderReceiptRepository}. Lets Spring wire
 * CheckoutService / MemberAccountService / SystemAdminService.
 * {@code @Profile("!jpa")}: the {@code jpa} run/dev profile swaps in
 * {@link JpaOrderReceiptRepository} instead.
 */
@Repository
@Profile("!jpa")
public class MemoryOrderReceiptRepository implements OrderReceiptRepository {

    private final Map<Integer, OrderReceipt> receiptsById = new ConcurrentHashMap<>();
    private final AtomicInteger idSequence = new AtomicInteger(1);
    private final RepositoryLocks<Integer> locks = new RepositoryLocks<>();
    

    @Override
    public void lockForUpdate(Integer id) {
        locks.lock(id);
    }

    @Override
    public void unlock(Integer id) {
        locks.unlock(id);
    }

    @Override
    public int nextId() {
        return idSequence.getAndIncrement();
    }



    @Override
    public void save(OrderReceipt orderReceipt) {
        if (orderReceipt == null) {
            throw new IllegalArgumentException("orderReceipt must not be null");
        }

        if (orderReceipt.getId() <= 0) {
            throw new IllegalArgumentException("orderReceipt id must be positive");
        }

        orderReceipt.checkInvariants();
        receiptsById.put(orderReceipt.getId(), orderReceipt);
    }

    
    
    @Override
    public Optional<OrderReceipt> findByOrderReceiptId(int orderReceiptId) {
        return Optional.ofNullable(receiptsById.get(orderReceiptId));
    }

    // UC-16 — member's own purchase history.
    @Override
    public List<OrderReceipt> findByHolderUserId(int holderUserId) {
        return receiptsById.values().stream()
                .filter(OrderReceipt::isMemberReceipt) // Only consider member receipts since only they have holderUserId
                .filter(receipt -> receipt.getHolderUserId().equals(holderUserId))
                .collect(Collectors.toList());
    }
    


    @Override
    public List<OrderReceipt> findByEventId(int eventId) {
        return receiptsById.values().stream()
                .filter(receipt -> receipt.containsEventId(eventId))
                .collect(Collectors.toList());
    }



    @Override
    public List<OrderReceipt> findByEventIds(List<Integer> eventIds) {
        if (eventIds == null || eventIds.isEmpty()) {
            return List.of();
        }

        return receiptsById.values().stream()
                .filter(receipt -> receipt.getReceiptLines().stream()
                .anyMatch(line -> eventIds.contains(line.getEventId())))
                .collect(Collectors.toList());
    }

    





    






    



    // UC-31 — global view with filters
    // A System Admin can query the full cross-company purchase history, with filters by buyer, production company, event, or date range.
    @Override
    public List<OrderReceipt> findGlobal(GlobalHistoryFiltersDTO filters) {
        if (filters == null) {
            // If no filters are provided, return all receipts.
            return List.copyOf(receiptsById.values());
        }

        return receiptsById.values().stream()
                .filter(receipt -> {
                    boolean matches = true;
                    
                    // Filter by buyerUserId if provided
                    if (filters.buyerUserId() != null) {
                        matches &= receipt.isMemberReceipt()
                                && receipt.getHolderUserId().equals(filters.buyerUserId());
                    }

                    // Filter by eventIds if provided
                    if (filters.eventIds() != null) {
                        matches &= receipt.getReceiptLines().stream()
                                .anyMatch(line -> filters.eventIds().contains(line.getEventId()));
                    }
                    
                    // Filter by date range if provided
                    if (filters.fromDate() != null) {
                        matches &= !receipt.getPurchaseTime()
                                .isBefore(filters.fromDate().atStartOfDay());
                    }

                    // Filter by date range if provided
                    if (filters.toDate() != null) {
                        matches &= !receipt.getPurchaseTime()
                                .isAfter(filters.toDate().atTime(23, 59, 59));
                    }

                    return matches;
                })
                .collect(Collectors.toList());
    }


    


}
