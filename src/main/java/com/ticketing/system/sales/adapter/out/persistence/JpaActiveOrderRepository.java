package com.ticketing.system.sales.adapter.out.persistence;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.ticketing.system.sales.domain.ActiveOrder;
import com.ticketing.system.sales.domain.CartLineItem;
import com.ticketing.system.sales.application.port.out.ActiveOrderRepository;
import com.ticketing.system.shared.exception.ConcurrentReservationException;
import com.ticketing.system.shared.persistence.ConstraintViolations;
import com.ticketing.system.shared.persistence.PersistenceProfiles;

/**
 * JPA-backed {@link ActiveOrderRepository} — active in every run but the in-memory test suite. Adapts
 * the domain port onto Spring Data ({@link SpringDataActiveOrderRepository}); the application layer
 * depends only on {@code ActiveOrderRepository}, never on Spring Data. Owned cart items
 * ({@code @ElementCollection}) persist by cascade with the order.
 *
 * <p>{@code lockForUpdate}/{@code unlock} are no-ops: concurrency is guarded by {@code ActiveOrder}'s
 * {@code @Version}. The guest→member cart-merge that previously relied on the double-lock in
 * {@code CartRestorationAdapter.mergeGuestCartOnPromotion} therefore runs optimistically — the merge is
 * re-runnable (re-read the cart, re-attach, save), so it survives as a {@code @Version} retry once the
 * service-level {@code @Retryable} lands (C1, #359).
 *
 * <p>{@link #save} reproduces the in-memory identity collapse: a member cart is unique per userId and
 * a guest cart per sessionId, even though each {@link ActiveOrder} carries a distinct orderKey. So a
 * save first entity-deletes any <em>other</em> cart sharing this cart's identity that holds nothing (the
 * entity delete cascades the item collection), refuses if one still holds lines, then persists this
 * one. {@code uk_active_orders_user_id} backs the member side in the database, because the order lock
 * here is a no-op. Writes are {@code @Transactional} so the
 * adapter is self-sufficient before the service layer gains transactions (#359).
 */
@Repository
@Profile(PersistenceProfiles.JPA_ADAPTERS)
public class JpaActiveOrderRepository implements ActiveOrderRepository {

    private static final String USER_CART_UNIQUE_CONSTRAINT = "uk_active_orders_user_id";

    private final SpringDataActiveOrderRepository data;

    public JpaActiveOrderRepository(SpringDataActiveOrderRepository data) {
        this.data = data;
    }

    @Override
    public void lockForUpdate(String id) { /* no-op — @Version optimistic locking */ }

    @Override
    public void unlock(String id) { /* no-op */ }

    /**
     * {@inheritDoc}
     *
     * <p>Flushes, so a member cart another request stored first fails here, on
     * {@code uk_active_orders_user_id}, as {@link ConcurrentReservationException}, rather than at a later
     * flush or at commit, outside the adapter. Any other integrity violation propagates unchanged.
     */
    @Override
    @Transactional
    public void save(ActiveOrder activeOrder) {
        // The whole body is guarded, not just the final write: a managed cart that was given a user id
        // (the login merge) is flushed by the first query below, and that UPDATE is where the key breaks.
        try {
            List<ActiveOrder> sameIdentity = activeOrder.isMember()
                    ? data.findByUserId(activeOrder.getUserId())
                    : data.findBySessionIdAndUserIdIsNull(activeOrder.getSessionId());
            for (ActiveOrder existing : sameIdentity) {
                if (!existing.getOrderKey().equals(activeOrder.getOrderKey())) {
                    if (!existing.getItems().isEmpty()) {
                        throw new ConcurrentReservationException(ActiveOrderSaveRules.ANOTHER_CART_HOLDS_LINES, null);
                    }
                    data.delete(existing); // entity delete cascades the @ElementCollection items
                }
            }
            // Hibernate flushes inserts before deletes, so the replaced cart's delete must reach the
            // database first, or this cart's insert would break uk_active_orders_user_id.
            data.flush();
            data.saveAndFlush(activeOrder);
        } catch (DataIntegrityViolationException e) {
            if (ConstraintViolations.violates(e, USER_CART_UNIQUE_CONSTRAINT)) {
                throw new ConcurrentReservationException(ActiveOrderSaveRules.ANOTHER_CART_HOLDS_LINES, e);
            }
            throw e;
        }
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
        // Hibernate runs a flush's updates before its deletes. The login merge deletes the member's old
        // cart and then gives the guest cart that member's user id; if both waited for one flush, the
        // update would break uk_active_orders_user_id before the delete ran.
        data.flush();
    }

    @Override
    public List<ActiveOrder> findExpired(LocalDateTime now) {
        LocalDateTime cutoff = now.minus(CartLineItem.getExpirationLimit());
        return data.findWithItemAddedBefore(cutoff);
    }

    @Override
    public List<ActiveOrder> findAll() {
        return data.findAll();
    }
}
