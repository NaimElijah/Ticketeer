package com.ticketing.system.sales.adapter.out.persistence;

import com.ticketing.system.shared.persistence.RepositoryLocks;
import com.ticketing.system.shared.persistence.PersistenceProfiles;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import com.ticketing.system.sales.domain.ActiveOrder;
import com.ticketing.system.sales.application.port.out.ActiveOrderRepository;
import com.ticketing.system.shared.exception.ConcurrentReservationException;

/**
 * In-memory {@link ActiveOrderRepository}, used only by the in-memory test suite ({@code test}
 * with {@code memory}); every other run gets {@link JpaActiveOrderRepository}.
 *
 * <p>
 * Storage is a single {@link CopyOnWriteArrayList} of carts; lookups scan.
 * Identity collapse for {@code save()} matches by userId for Member carts and
 * by sessionId for Guest carts, so re-saving a promoted cart replaces a stale
 * Member-cart row with the same userId that holds nothing, and refuses one that
 * still holds lines (D9a edge: prefer current Guest-turned-Member cart over a
 * prior orphaned Member cart, whose lines the login merge releases first).
 */
@Repository
@Profile(PersistenceProfiles.IN_MEMORY_ADAPTERS)
public class MemoryActiveOrderRepository implements ActiveOrderRepository {

    private final List<ActiveOrder> carts = new CopyOnWriteArrayList<>();
    private final RepositoryLocks<String> locks = new RepositoryLocks<>();   // Key is "user:{userId}" for Member carts, "sess:{sessionId}" for Guest carts.

    @Override
    public void lockForUpdate(String id) { locks.lock(id); }

    @Override
    public void unlock(String id) { locks.unlock(id); }

    @Override
    public void save(ActiveOrder activeOrder) {
        // If a cart with the same identity already exists, the caller must hold
        // the canonical lock for that cart before overwriting it.
        // First-time saves (no prior cart with this identity) are allowed without
        // a lock — no other thread can race on a cart that doesn't exist yet.
        boolean existingCart = carts.stream().anyMatch(existing -> sameIdentity(existing, activeOrder));
        if (existingCart) {
            String lockKey = activeOrder.isMember()
                    ? "user:" + activeOrder.getUserId()
                    : "sess:" + activeOrder.getSessionId();
            if (!locks.isHeldByCurrentThread(lockKey)) {
                throw new IllegalStateException(
                        "ActiveOrder for " + lockKey + " must be locked via lockForUpdate before saving");
            }
        }
        // Replace any other cart with the same identity, unless it still holds lines: deleting those would
        // leave their seats reserved under an order key no cart holds.
        boolean anotherCartHoldsLines = carts.stream().anyMatch(existing -> sameIdentity(existing, activeOrder)
                && !existing.getOrderKey().equals(activeOrder.getOrderKey())
                && !existing.getItems().isEmpty());
        if (anotherCartHoldsLines) {
            throw new ConcurrentReservationException(ActiveOrderSaveRules.ANOTHER_CART_HOLDS_LINES, null);
        }
        carts.removeIf(existing -> sameIdentity(existing, activeOrder));
        carts.add(activeOrder);
    }

    @Override
    public ActiveOrder getByUserId(int userId) {
        Integer target = userId;
        for (ActiveOrder cart : carts) {
            if (target.equals(cart.userIdOrNull())) {
                return cart;
            }
        }
        return null;
    }

    @Override
    public Optional<ActiveOrder> getBySessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        for (ActiveOrder cart : carts) {
            if (sessionId.equals(cart.getSessionId())) {
                return Optional.of(cart);
            }
        }
        return Optional.empty();
    }

    @Override
    public void delete(ActiveOrder activeOrder) {
        if (activeOrder == null)
            return;
        carts.remove(activeOrder);
    }

    @Override
    public List<ActiveOrder> findExpired(LocalDateTime now) {

        List<ActiveOrder> result = new ArrayList<>();
        for (ActiveOrder cart : carts) {
            if (cart.hasExpiredItem(now)) {
                result.add(cart);
            }
        }
        return result;
    }

    @Override
    public List<ActiveOrder> findAll() {
        return new ArrayList<>(carts);
    }

    private boolean sameIdentity(ActiveOrder a, ActiveOrder b) {
        // Member identity collapses on userId.
        if (a.userIdOrNull() != null && a.userIdOrNull().equals(b.userIdOrNull())) {
            return true;
        }
        // Pure-Guest identity collapses on sessionId.
        if (a.userIdOrNull() == null && b.userIdOrNull() == null
                && a.getSessionId() != null
                && a.getSessionId().equals(b.getSessionId())) {
            return true;
        }
        return false;
    }
}
