package com.ticketing.system.sales.adapter.out.persistence;
import com.ticketing.system.identity.application.service.AuthenticationService;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.ticketing.system.sales.domain.ActiveOrder;
import com.ticketing.system.sales.domain.CartLineItem;
import com.ticketing.system.sales.application.port.out.ActiveOrderRepository;

/**
 * JPA-backed {@link ActiveOrderRepository} — active only in the {@code jpa} run/dev profile. Adapts
 * the domain port onto Spring Data ({@link SpringDataActiveOrderRepository}); the application layer
 * depends only on {@code ActiveOrderRepository}, never on Spring Data. Owned cart items
 * ({@code @ElementCollection}) persist by cascade with the order.
 *
 * <p>{@code lockForUpdate}/{@code unlock} are no-ops: concurrency is guarded by {@code ActiveOrder}'s
 * {@code @Version}. The guest→member cart-merge that previously relied on the double-lock in
 * {@code AuthenticationService.handleCartOnPromotion} therefore runs optimistically — the merge is
 * re-runnable (re-read the cart, re-attach, save), so it survives as a {@code @Version} retry once the
 * service-level {@code @Retryable} lands (C1, #359).
 *
 * <p>{@link #save} reproduces the in-memory identity collapse: a member cart is unique per userId and
 * a guest cart per sessionId, even though each {@link ActiveOrder} carries a distinct orderKey. So a
 * save first entity-deletes any <em>other</em> cart sharing this cart's identity (the entity delete
 * cascades the item collection), then persists this one. Writes are {@code @Transactional} so the
 * adapter is self-sufficient before the service layer gains transactions (#359).
 */
@Repository
@Profile("jpa")
public class JpaActiveOrderRepository implements ActiveOrderRepository {

    private final SpringDataActiveOrderRepository data;

    public JpaActiveOrderRepository(SpringDataActiveOrderRepository data) {
        this.data = data;
    }

    @Override
    public void lockForUpdate(String id) { /* no-op — @Version optimistic locking */ }

    @Override
    public void unlock(String id) { /* no-op */ }

    @Override
    @Transactional
    public void save(ActiveOrder activeOrder) {
        List<ActiveOrder> sameIdentity = activeOrder.isMember()
                ? data.findByUserId(activeOrder.getUserId())
                : data.findBySessionIdAndUserIdIsNull(activeOrder.getSessionId());
        for (ActiveOrder existing : sameIdentity) {
            if (!existing.getOrderKey().equals(activeOrder.getOrderKey())) {
                data.delete(existing); // entity delete cascades the @ElementCollection items
            }
        }
        data.save(activeOrder);
    }

    @Override
    public ActiveOrder getByUserId(int userId) {
        return data.findByUserId(userId).stream().findFirst().orElse(null);
    }

    @Override
    public Optional<ActiveOrder> getBySessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        return data.findFirstBySessionId(sessionId);
    }

    @Override
    @Transactional
    public void delete(ActiveOrder activeOrder) {
        if (activeOrder == null) {
            return;
        }
        // Idempotent + cascades the item collection; deleting an unknown cart is a no-op.
        data.findById(activeOrder.getOrderKey()).ifPresent(data::delete);
    }

    @Override
    public List<ActiveOrder> findExpired() {
        LocalDateTime cutoff = LocalDateTime.now().minus(CartLineItem.getExpirationLimit());
        return data.findWithItemAddedBefore(cutoff);
    }

    @Override
    public List<ActiveOrder> findAll() {
        return data.findAll();
    }
}
