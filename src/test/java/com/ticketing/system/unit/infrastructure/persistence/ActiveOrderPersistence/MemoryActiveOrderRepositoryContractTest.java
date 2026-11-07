package com.ticketing.system.unit.infrastructure.persistence.ActiveOrderPersistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.ticketing.system.sales.application.port.out.ActiveOrderRepository;
import com.ticketing.system.sales.adapter.out.persistence.MemoryActiveOrderRepository;
import com.ticketing.system.sales.domain.ActiveOrder;

class MemoryActiveOrderRepositoryContractTest extends IActiveOrderRepositoryContractTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2027, 7, 15, 17, 0);

    @Override
    protected ActiveOrderRepository newRepository() {
        return new MemoryActiveOrderRepository();
    }

    // === save of a stored cart — lock enforcement (Memory-specific) ===
    // This adapter has no @Version, so it enforces the locking discipline itself: a cart that is already
    // stored is saved again only under its lock, keyed "user:<id>" or "sess:<sid>". The Jpa adapter
    // accepts the same save (JpaActiveOrderRepositoryContractTest).

    @Test
    void GivenAStoredMemberCart_WhenSavedAgainWithoutItsLock_ThenTheSaveIsRefused() {
        ActiveOrderRepository repo = newRepository();
        ActiveOrder cart = ActiveOrder.forMember(5, "sid-A", NOW);
        repo.save(cart);

        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> repo.save(cart));

        assertEquals("ActiveOrder for user:5 must be locked via lockForUpdate before saving", refused.getMessage());
        assertEquals(List.of(cart.getOrderKey()), repo.findAll().stream().map(ActiveOrder::getOrderKey).toList());
    }

    @Test
    void GivenAStoredGuestCart_WhenSavedAgainWithoutItsLock_ThenTheSaveIsRefused() {
        ActiveOrderRepository repo = newRepository();
        ActiveOrder cart = ActiveOrder.forGuest("sid-G", NOW);
        repo.save(cart);

        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> repo.save(cart));

        assertEquals("ActiveOrder for sess:sid-G must be locked via lockForUpdate before saving", refused.getMessage());
        assertEquals(List.of(cart.getOrderKey()), repo.findAll().stream().map(ActiveOrder::getOrderKey).toList());
    }
}
