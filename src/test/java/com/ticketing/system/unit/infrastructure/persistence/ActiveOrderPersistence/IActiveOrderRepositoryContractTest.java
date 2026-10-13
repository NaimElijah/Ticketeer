package com.ticketing.system.unit.infrastructure.persistence.ActiveOrderPersistence;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.sales.domain.ActiveOrder;
import com.ticketing.system.sales.application.port.out.ActiveOrderRepository;

// Contract tests every ActiveOrderRepository implementation must satisfy. Future
// JPA-backed adapter will subclass this with its own newRepository() factory;
// tests are reused.
abstract class IActiveOrderRepositoryContractTest {

    protected abstract ActiveOrderRepository newRepository();

    private ActiveOrderRepository repo;

    @BeforeEach
    void setUp() {
        repo = newRepository();
    }

    @Test
    void save_thenGetByUserId_returnsMemberCart() {
        ActiveOrder member = ActiveOrder.forMember(5, "sid-A");
        repo.save(member);

        ActiveOrder found = repo.getByUserId(5);
        assertNotNull(found);
        assertEquals(5, found.getUserId());
        assertEquals("sid-A", found.getSessionId());
    }

    @Test
    void getByUserId_returnsNullWhenMissing() {
        assertNull(repo.getByUserId(99));
    }

    @Test
    void save_thenGetBySessionId_returnsGuestCart() {
        ActiveOrder guest = ActiveOrder.forGuest("sid-G");
        repo.save(guest);

        Optional<ActiveOrder> found = repo.getBySessionId("sid-G");
        assertTrue(found.isPresent());
        assertTrue(found.get().isGuest());
    }

    @Test
    void getBySessionId_returnsEmptyWhenMissing() {
        assertTrue(repo.getBySessionId("ghost-sid").isEmpty());
    }

    @Test
    void getBySessionId_returnsMemberCartWhenSessionMatches() {
        // Member cart with active session — same lookup path as Guest.
        ActiveOrder member = ActiveOrder.forMember(7, "sid-M");
        repo.save(member);

        Optional<ActiveOrder> found = repo.getBySessionId("sid-M");
        assertTrue(found.isPresent());
        assertEquals(7, found.get().getUserId());
    }

    @Test
    void getBySessionId_nullReturnsEmpty() {
        assertTrue(repo.getBySessionId(null).isEmpty());
    }

    @Test
    void getBySessionId_blankReturnsEmpty() {
        assertTrue(repo.getBySessionId("   ").isEmpty());
    }

    @Test
    void delete_removesCart() {
        ActiveOrder cart = ActiveOrder.forMember(5, "sid-A");
        repo.save(cart);
        assertNotNull(repo.getByUserId(5));

        repo.delete(cart);
        assertNull(repo.getByUserId(5));
    }

    @Test
    void delete_unknownIsNoOp() {
        ActiveOrder cart = ActiveOrder.forMember(99, "sid-Z");
        assertDoesNotThrow(() -> repo.delete(cart));
        assertDoesNotThrow(() -> repo.delete(null));
    }

    @Test
    void save_sameMemberTwiceCollapsesIdentity() {
        // D9a edge: re-saving a Member cart (e.g., after promotion) replaces
        // any prior cart with the same userId.
        ActiveOrder cartV1 = ActiveOrder.forMember(5, "sid-A");
        cartV1.addStandingReservation(1, 10, 1, 50.0, java.time.LocalDateTime.now());
        repo.save(cartV1);

        ActiveOrder cartV2 = ActiveOrder.forMember(5, "sid-B");
        repo.lockForUpdate("user:5");
        try {
            repo.save(cartV2);
        } finally {
            repo.unlock("user:5");
        }

        // Only one cart for user 5 — the second one wins.
        assertEquals("sid-B", repo.getByUserId(5).getSessionId());
        assertEquals(0, repo.getByUserId(5).getItems().size());
    }

    @Test
    void save_guestAndMemberWithSameSessionId_stayDistinct() {
        // Edge: a Guest cart and a Member cart that happen to share a sessionId
        // should remain distinct rows (different identities).
        ActiveOrder guest = ActiveOrder.forGuest("shared-sid");
        ActiveOrder member = ActiveOrder.forMember(5, "shared-sid");
        repo.save(guest);
        repo.save(member);

        // Both retrievable.
        assertNotNull(repo.getByUserId(5));
        // getBySessionId hits one of them; either is OK for this contract.
        assertTrue(repo.getBySessionId("shared-sid").isPresent());
    }

    @Test
    void findExpired_emptyWhenNoExpiredItems() {
        ActiveOrder cart = ActiveOrder.forGuest("sid-G");
        cart.addStandingReservation(1, 10, 1, 25.0, java.time.LocalDateTime.now());
        repo.save(cart);
        assertTrue(repo.findExpired().isEmpty());
    }

    @Test
    void findAll_returnsEveryCart() {
        repo.save(ActiveOrder.forMember(5, "sid-M"));
        repo.save(ActiveOrder.forGuest("sid-G"));

        java.util.List<ActiveOrder> all = repo.findAll();
        assertEquals(2, all.size());
    }

    @Test
    void findAll_emptyWhenNoCarts() {
        assertTrue(repo.findAll().isEmpty());
    }

    @Test
    void save_persistsCartItems() {
        // A member cart with items survives save/reload (the "cart survives a restart" acceptance).
        ActiveOrder cart = ActiveOrder.forMember(5, "sid-A");
        cart.addStandingReservation(1, 10, 2, 50.0, java.time.LocalDateTime.now());          // 2 standing
        cart.addSeatedReservation(1, 11, java.util.List.of("A1"), 75.0, java.time.LocalDateTime.now()); // 1 seated
        repo.save(cart);

        ActiveOrder found = repo.getByUserId(5);
        assertNotNull(found);
        assertEquals(3, found.getItems().size());
        assertTrue(found.getItems().stream()
                .anyMatch(i -> i.geteventId() == 1 && i.getzoneId() == 11 && "A1".equals(i.getSeatNumber())));
    }
}
