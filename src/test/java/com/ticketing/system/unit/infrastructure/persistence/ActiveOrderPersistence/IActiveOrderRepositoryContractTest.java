package com.ticketing.system.unit.infrastructure.persistence.ActiveOrderPersistence;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.sales.domain.ActiveOrder;
import com.ticketing.system.sales.domain.CartLineItem;
import com.ticketing.system.sales.application.port.out.ActiveOrderRepository;
import com.ticketing.system.shared.exception.ConcurrentReservationException;

// Contract tests every ActiveOrderRepository implementation must satisfy. Future
// JPA-backed adapter will subclass this with its own newRepository() factory;
// tests are reused.
abstract class IActiveOrderRepositoryContractTest {

    protected abstract ActiveOrderRepository newRepository();

    private ActiveOrderRepository repo;

    // The adapters judge expiry at the caller's now (UTC wall time from the injected clock, ADR-008).
    private static final LocalDateTime NOW = LocalDateTime.of(2027, 7, 15, 17, 0);

    @BeforeEach
    void setUp() {
        repo = newRepository();
    }

    @Test
    void save_thenGetByUserId_returnsMemberCart() {
        ActiveOrder member = ActiveOrder.forMember(5, "sid-A", NOW);
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
        ActiveOrder guest = ActiveOrder.forGuest("sid-G", NOW);
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
        ActiveOrder member = ActiveOrder.forMember(7, "sid-M", NOW);
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
        ActiveOrder cart = ActiveOrder.forMember(5, "sid-A", NOW);
        repo.save(cart);
        assertNotNull(repo.getByUserId(5));

        repo.delete(cart);
        assertNull(repo.getByUserId(5));
    }

    @Test
    void delete_unknownIsNoOp() {
        ActiveOrder cart = ActiveOrder.forMember(99, "sid-Z", NOW);
        assertDoesNotThrow(() -> repo.delete(cart));
        assertDoesNotThrow(() -> repo.delete(null));
    }

    @Test
    void save_sameMemberTwiceCollapsesIdentity() {
        // D9a edge: re-saving a Member cart (e.g., after promotion) replaces
        // any prior cart with the same userId that holds nothing.
        ActiveOrder cartV1 = ActiveOrder.forMember(5, "sid-A", NOW);
        repo.save(cartV1);

        ActiveOrder cartV2 = ActiveOrder.forMember(5, "sid-B", NOW);
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

    // Deleting a cart that still holds lines would leave its seats reserved under an order key that no
    // cart holds, so nothing would ever release them. Its buyer's lines are released first (the login
    // merge abandons the replaced cart), or the save is refused as a concurrent change.
    @Test
    void GivenAnotherCartOfTheMemberHoldingLines_WhenSavingANewCart_ThenTheSaveIsRefusedAndTheFirstCartStays() {
        ActiveOrder holding = ActiveOrder.forMember(5, "sid-A", NOW);
        holding.addStandingReservation(1, 10, 1, 50.0, NOW);
        repo.save(holding);

        ActiveOrder second = ActiveOrder.forMember(5, "sid-B", NOW);
        repo.lockForUpdate("user:5");
        try {
            assertThrows(ConcurrentReservationException.class, () -> repo.save(second));
        } finally {
            repo.unlock("user:5");
        }

        ActiveOrder kept = repo.getByUserId(5);
        assertEquals(holding.getOrderKey(), kept.getOrderKey(), "the cart holding lines stays");
        assertEquals(1, kept.getItems().size());
    }

    @Test
    void GivenAnotherGuestCartOfTheSessionHoldingLines_WhenSavingANewGuestCart_ThenTheSaveIsRefusedAndTheFirstCartStays() {
        ActiveOrder holding = ActiveOrder.forGuest("sid-G", NOW);
        holding.addStandingReservation(1, 10, 1, 50.0, NOW);
        repo.save(holding);

        ActiveOrder second = ActiveOrder.forGuest("sid-G", NOW);
        repo.lockForUpdate("sess:sid-G");
        try {
            assertThrows(ConcurrentReservationException.class, () -> repo.save(second));
        } finally {
            repo.unlock("sess:sid-G");
        }

        ActiveOrder kept = repo.getBySessionId("sid-G").orElseThrow();
        assertEquals(holding.getOrderKey(), kept.getOrderKey(), "the cart holding lines stays");
        assertEquals(1, kept.getItems().size());
    }

    @Test
    void save_guestAndMemberWithSameSessionId_stayDistinct() {
        // Edge: a Guest cart and a Member cart that happen to share a sessionId
        // should remain distinct rows (different identities).
        ActiveOrder guest = ActiveOrder.forGuest("shared-sid", NOW);
        ActiveOrder member = ActiveOrder.forMember(5, "shared-sid", NOW);
        repo.save(guest);
        repo.save(member);

        // Both retrievable.
        assertNotNull(repo.getByUserId(5));
        // getBySessionId hits one of them; either is OK for this contract.
        assertTrue(repo.getBySessionId("shared-sid").isPresent());
    }

    @Test
    void findExpired_emptyWhenNoExpiredItems() {
        ActiveOrder cart = ActiveOrder.forGuest("sid-G", NOW);
        cart.addStandingReservation(1, 10, 1, 25.0, NOW);
        repo.save(cart);
        assertTrue(repo.findExpired(NOW).isEmpty());
    }

    // Both adapters treat a hold as run out once a full hold has elapsed (Jpa: addedAt <= now - hold;
    // Memory: no time left at now), judged at the caller's now rather than the system clock (#74).
    @Test
    void GivenItemAddedExactlyOneHoldBeforeNow_WhenFindExpired_ThenTheCartIsReturned() {
        ActiveOrder cart = ActiveOrder.forGuest("sid-old", NOW);
        cart.addStandingReservation(1, 10, 1, 25.0, NOW.minus(CartLineItem.getExpirationLimit()));
        repo.save(cart);

        assertEquals(1, repo.findExpired(NOW).size());
    }

    // Recorded times are UTC wall time (ADR-008), so they may fall in the hour a JVM zone skips when its
    // clocks go forward: Asia/Jerusalem has no 02:00-03:00 on 26 March 2027. Storage must not move such a
    // time. This bites only on a JVM in such a zone, which CI's "Test in Asia/Jerusalem" job runs.
    @Test
    void GivenItemAddedInTheHourTheJvmZoneSkips_WhenFindExpired_ThenItExpiresOneHoldLater() {
        LocalDateTime addedAt = LocalDateTime.of(2027, 3, 26, 2, 50);
        ActiveOrder cart = ActiveOrder.forGuest("sid-gap", addedAt);
        cart.addStandingReservation(1, 10, 1, 25.0, addedAt);
        repo.save(cart);

        LocalDateTime tenMinutesLater = LocalDateTime.of(2027, 3, 26, 3, 0).plus(CartLineItem.getExpirationLimit());
        assertEquals(1, repo.findExpired(tenMinutesLater).size());
    }

    @Test
    void GivenItemAddedJustUnderOneHoldBeforeNow_WhenFindExpired_ThenNothingIsReturned() {
        ActiveOrder cart = ActiveOrder.forGuest("sid-fresh", NOW);
        cart.addStandingReservation(1, 10, 1, 25.0, NOW.minus(CartLineItem.getExpirationLimit()).plusSeconds(1));
        repo.save(cart);

        assertTrue(repo.findExpired(NOW).isEmpty());
    }

    @Test
    void findAll_returnsEveryCart() {
        repo.save(ActiveOrder.forMember(5, "sid-M", NOW));
        repo.save(ActiveOrder.forGuest("sid-G", NOW));

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
        ActiveOrder cart = ActiveOrder.forMember(5, "sid-A", NOW);
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
