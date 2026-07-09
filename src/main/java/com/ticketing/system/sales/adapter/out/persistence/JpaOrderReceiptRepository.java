package com.ticketing.system.sales.adapter.out.persistence;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.context.annotation.Profile;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.ticketing.system.shared.dto.GlobalHistoryFiltersDTO;
import com.ticketing.system.sales.application.port.out.OrderReceiptRepository;
import com.ticketing.system.sales.domain.OrderReceipt;

import jakarta.persistence.criteria.Predicate;

/**
 * JPA-backed {@link OrderReceiptRepository} — active only in the {@code jpa} run/dev profile.
 * Adapts the domain port onto Spring Data ({@link SpringDataOrderReceiptRepository}); the application
 * layer depends only on {@code OrderReceiptRepository}, never on Spring Data. Owned receipt-line and
 * transaction value lists ({@code @ElementCollection}) persist by cascade with the receipt.
 *
 * <p>{@code lockForUpdate} issues a real {@code SELECT … FOR UPDATE} (the refund critical section's row
 * lock, #410); {@code unlock} is a no-op because the lock releases at transaction commit. {@code save}
 * delegates to {@code data.save} under {@code @Version} and is {@code @Transactional}. {@link #nextId()}
 * keeps the assigned-id design but seeds an in-memory counter from {@code max(receiptId)} on first use
 * so ids survive a restart. {@link #findGlobal} assembles the optional admin filters into a dynamic
 * {@link Specification} (it ignores {@code companyId}, matching the in-memory impl — a receipt does not
 * know company ownership directly).
 */
@Repository
@Profile("jpa")
public class JpaOrderReceiptRepository implements OrderReceiptRepository {

    private final SpringDataOrderReceiptRepository data;
    private final AtomicInteger idSequence = new AtomicInteger(0);
    private volatile boolean seeded = false;

    public JpaOrderReceiptRepository(SpringDataOrderReceiptRepository data) {
        this.data = data;
    }

    @Override
    public void lockForUpdate(Integer id) {
        // Real pessimistic row-lock (SELECT … FOR UPDATE) on the receipt — serialises the refund
        // critical section (#410). Requires an active transaction (RefundService wraps it in one); the
        // lock is held until that transaction commits, so a concurrent refund of the same receipt blocks
        // here, then sees is_refunded=true and bails before a second gateway.refund(). Other writes use
        // optimistic @Version, but @Version can't prevent the double gateway call (it fails only at
        // commit, after the gateway was already hit), so this one path is pessimistic.
        data.findByIdForUpdate(id);
    }

    @Override
    public void unlock(Integer id) { /* no-op — the pessimistic lock releases at transaction commit */ }

    @Override
    public int nextId() {
        ensureSeeded();
        return idSequence.incrementAndGet();
    }

    private void ensureSeeded() {
        if (!seeded) {
            synchronized (this) {
                if (!seeded) {
                    idSequence.set(data.findMaxReceiptId());
                    seeded = true;
                }
            }
        }
    }

    @Override
    @Transactional
    public void save(OrderReceipt orderReceipt) {
        data.save(orderReceipt);
    }

    @Override
    public Optional<OrderReceipt> findByOrderReceiptId(int orderReceiptId) {
        return data.findById(orderReceiptId);
    }

    @Override
    public List<OrderReceipt> findByHolderUserId(int holderUserId) {
        return data.findByUserid(holderUserId);
    }

    @Override
    public List<OrderReceipt> findByEventIds(List<Integer> eventIds) {
        if (eventIds == null || eventIds.isEmpty()) {
            return List.of();
        }
        return data.findByEventIds(eventIds);
    }

    @Override
    public List<OrderReceipt> findByEventId(int eventId) {
        return data.findByEventId(eventId);
    }

    @Override
    public List<OrderReceipt> findGlobal(GlobalHistoryFiltersDTO filters) {
        if (filters == null) {
            return data.findAll();
        }
        Specification<OrderReceipt> spec = (root, query, cb) -> {
            query.distinct(true);
            List<Predicate> predicates = new ArrayList<>();
            if (filters.buyerUserId() != null) {
                predicates.add(cb.equal(root.get("userid"), filters.buyerUserId()));
            }
            if (filters.eventIds() != null && !filters.eventIds().isEmpty()) {
                predicates.add(root.join("receiptLines").get("eventid").in(filters.eventIds()));
            }
            if (filters.fromDate() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("purchaseTime"), filters.fromDate().atStartOfDay()));
            }
            if (filters.toDate() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("purchaseTime"), filters.toDate().atTime(23, 59, 59)));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        return data.findAll(spec);
    }
}
