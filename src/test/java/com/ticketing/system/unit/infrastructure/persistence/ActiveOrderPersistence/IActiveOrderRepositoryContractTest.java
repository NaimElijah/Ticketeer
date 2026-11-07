package com.ticketing.system.unit.infrastructure.persistence.ActiveOrderPersistence;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.ticketing.system.sales.domain.ActiveOrder;
import com.ticketing.system.sales.domain.ActiveOrderStatus;
import com.ticketing.system.sales.domain.CartLineItem;
import com.ticketing.system.sales.application.port.out.ActiveOrderRepository;
import com.ticketing.system.shared.exception.ConcurrentReservationException;
import com.ticketing.system.testutil.RepositoryLockProbe;

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
    void GivenSavedMemberCart_WhenGetByUserId_ThenReturnsMemberCart() {
        ActiveOrder member = ActiveOrder.forMember(5, "sid-A", NOW);
        repo.save(member);

        ActiveOrder found = repo.getByUserId(5);
        assertNotNull(found);
        assertEquals(5, found.getUserId());
        assertEquals("sid-A", found.getSessionId());
    }

    @Test
    void GivenNoCartForTheUser_WhenGetByUserId_ThenReturnsNull() {
        assertNull(repo.getByUserId(99));
    }

    @Test
    void GivenSavedGuestCart_WhenGetBySessionId_ThenReturnsGuestCart() {
        ActiveOrder guest = ActiveOrder.forGuest("sid-G", NOW);
        repo.save(guest);

        Optional<ActiveOrder> found = repo.getBySessionId("sid-G");
        assertTrue(found.isPresent());
        assertTrue(found.get().isGuest());
    }

    @Test
    void GivenNoCartForTheSession_WhenGetBySessionId_ThenReturnsEmpty() {
        assertTrue(repo.getBySessionId("ghost-sid").isEmpty());
    }

    // getBySessionId is the guest-cart lookup: a member's cart is found by its user id only, even on the
    // session it was made in.
    @Test
    void GivenOnlyAMemberCartOnTheSession_WhenGetBySessionId_ThenReturnsEmpty() {
        repo.save(ActiveOrder.forMember(7, "sid-M", NOW));

        assertTrue(repo.getBySessionId("sid-M").isEmpty(), "a member cart must not be returned as the session's cart");
    }

    @Test
    void GivenNullSessionId_WhenGetBySessionId_ThenReturnsEmpty() {
        assertTrue(repo.getBySessionId(null).isEmpty());
    }

    @Test
    void GivenBlankSessionId_WhenGetBySessionId_ThenReturnsEmpty() {
        assertTrue(repo.getBySessionId("   ").isEmpty());
    }

    @Test
    void GivenSavedCart_WhenDelete_ThenRemovesCart() {
        ActiveOrder cart = ActiveOrder.forMember(5, "sid-A", NOW);
        repo.save(cart);
        assertNotNull(repo.getByUserId(5));

        repo.delete(cart);
        assertNull(repo.getByUserId(5));
    }

    @Test
    void GivenUnknownOrNullCart_WhenDelete_ThenDoesNotThrowAndTheStoredCartStays() {
        ActiveOrder stored = ActiveOrder.forMember(5, "sid-A", NOW);
        repo.save(stored);
        ActiveOrder unknown = ActiveOrder.forMember(99, "sid-Z", NOW);

        assertDoesNotThrow(() -> repo.delete(unknown));
        assertDoesNotThrow(() -> repo.delete(null));

        assertEquals(List.of(stored.getOrderKey()), repo.findAll().stream().map(ActiveOrder::getOrderKey).toList());
    }

    // A reservation, a removal and the sweeper all load the cart, change its lines and save it again
    // under the cart's lock. The Memory adapter refuses that save without the lock
    // (MemoryActiveOrderRepositoryContractTest); the Jpa adapter relies on @Version and needs none.
    @Test
    void GivenASavedCartWithLines_WhenLoadedChangedAndSavedUnderItsLock_ThenTheStoredCartHoldsTheNewLines() {
        ActiveOrder cart = ActiveOrder.forMember(5, "sid-A", NOW);
        cart.addStandingReservation(1, 10, 2, 50.0, NOW);
        repo.save(cart);

        repo.lockForUpdate("user:5");
        try {
            ActiveOrder loaded = repo.getByUserId(5);
            loaded.removeStandingSpots(1, 10, 1);
            loaded.addSeatedReservation(1, 11, List.of("B2"), 75.0, NOW);
            repo.save(loaded);
        } finally {
            repo.unlock("user:5");
        }

        ActiveOrder reloaded = repo.getByUserId(5);
        assertEquals(cart.getOrderKey(), reloaded.getOrderKey());
        assertEquals(List.of("1/10/standing", "1/11/B2"), lines(reloaded));
        assertEquals(1, repo.findAll().size(), "the cart is updated, not stored a second time");
    }

    private static List<String> lines(ActiveOrder cart) {
        return cart.getItems().stream()
                .map(i -> i.geteventId() + "/" + i.getzoneId() + "/"
                        + (i.getSeatNumber() == null ? "standing" : i.getSeatNumber()))
                .sorted().toList();
    }

    @Test
    void GivenAnotherCartOfTheMemberHoldingNothing_WhenSavingANewCart_ThenTheSecondCartWins() {
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

    // The guest twin of the member case above. A database key on the guest session (#224) must not break
    // it: the replaced cart's row has to be gone before the new cart's row is written.
    @Test
    void GivenAnotherGuestCartOfTheSessionHoldingNothing_WhenSavingANewGuestCart_ThenTheSecondCartWins() {
        repo.save(ActiveOrder.forGuest("sid-G", NOW));

        ActiveOrder second = ActiveOrder.forGuest("sid-G", NOW);
        repo.lockForUpdate("sess:sid-G");
        try {
            repo.save(second);
        } finally {
            repo.unlock("sess:sid-G");
        }

        assertEquals(1, repo.findAll().size(), "the session keeps one cart");
        assertEquals(second.getOrderKey(), repo.getBySessionId("sid-G").orElseThrow().getOrderKey(),
                "the second cart replaced the empty one");
    }

    // A guest cart and a member cart that share a session id stay distinct carts, and the session's cart
    // is the guest's, whichever was stored first: every caller of getBySessionId is a guest path.
    @Test
    void GivenAGuestCartThenAMemberCartOnOneSession_WhenGetBySessionId_ThenReturnsTheGuestCart() {
        ActiveOrder guest = ActiveOrder.forGuest("shared-sid", NOW);
        ActiveOrder member = ActiveOrder.forMember(5, "shared-sid", NOW);
        repo.save(guest);
        repo.save(member);

        assertSessionCartIsTheGuestCart(guest, member);
    }

    @Test
    void GivenAMemberCartThenAGuestCartOnOneSession_WhenGetBySessionId_ThenReturnsTheGuestCart() {
        ActiveOrder member = ActiveOrder.forMember(5, "shared-sid", NOW);
        ActiveOrder guest = ActiveOrder.forGuest("shared-sid", NOW);
        repo.save(member);
        repo.save(guest);

        assertSessionCartIsTheGuestCart(guest, member);
    }

    private void assertSessionCartIsTheGuestCart(ActiveOrder guest, ActiveOrder member) {
        assertEquals(guest.getOrderKey(), repo.getBySessionId("shared-sid").orElseThrow().getOrderKey(),
                "the session's cart is its guest cart");
        assertEquals(member.getOrderKey(), repo.getByUserId(5).getOrderKey(), "the member cart is still stored");
    }

    @Test
    void GivenCartWithNoExpiredItems_WhenFindExpired_ThenNothingIsReturned() {
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
    void GivenMemberAndGuestCarts_WhenFindAll_ThenReturnsEveryCart() {
        ActiveOrder member = ActiveOrder.forMember(5, "sid-M", NOW);
        ActiveOrder guest = ActiveOrder.forGuest("sid-G", NOW);
        repo.save(member);
        repo.save(guest);

        assertEquals(Set.of(member.getOrderKey(), guest.getOrderKey()),
                repo.findAll().stream().map(ActiveOrder::getOrderKey).collect(Collectors.toSet()));
    }

    @Test
    void GivenNoCarts_WhenFindAll_ThenReturnsEmpty() {
        assertTrue(repo.findAll().isEmpty());
    }

    // PRE_CHECKOUT is stored by every other case here. The Jpa table holds the status under a check
    // constraint that a migration must widen for each new constant (ADR-007).
    @Test
    void GivenACartFrozenForCheckout_WhenSavedAndFound_ThenItIsStillInCheckout() {
        ActiveOrder cart = ActiveOrder.forMember(5, "sid-A", NOW);
        cart.addStandingReservation(1, 10, 1, 50.0, NOW);
        cart.markCheckoutInProgress();
        repo.save(cart);

        assertEquals(ActiveOrderStatus.CHECKOUT_IN_PROGRESS, repo.getByUserId(5).getStatus());
    }

    @Test
    void GivenMemberCartWithStandingAndSeatedItems_WhenSave_ThenPersistsCartItems() {
        // A member cart with items survives save/reload (the "cart survives a restart" acceptance).
        ActiveOrder cart = ActiveOrder.forMember(5, "sid-A", NOW);
        cart.addStandingReservation(1, 10, 2, 50.0, NOW);          // 2 standing
        cart.addSeatedReservation(1, 11, java.util.List.of("A1"), 75.0, NOW); // 1 seated
        repo.save(cart);

        ActiveOrder found = repo.getByUserId(5);
        assertNotNull(found);
        assertEquals(3, found.getItems().size());
        assertTrue(found.getItems().stream()
                .anyMatch(i -> i.geteventId() == 1 && i.getzoneId() == 11 && "A1".equals(i.getSeatNumber())));
    }

    // IRepository's lock contract: reentrant, and released by one unlock per lock. The second
    // lockForUpdate would hang a non-reentrant lock, hence the timeout on a separate thread. The Jpa
    // adapter makes both calls no-ops (@Version guards its writes), so it passes trivially.
    @Test
    @Timeout(value = RepositoryLockProbe.TEST_TIMEOUT_SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void GivenALockTakenTwiceAndReleasedTwice_WhenAnotherThreadLocksTheSameId_ThenItGetsTheLock() throws Exception {
        repo.lockForUpdate("user:5");
        repo.lockForUpdate("user:5");
        repo.unlock("user:5");
        repo.unlock("user:5");

        RepositoryLockProbe.assertAnotherThreadCanLock(repo, "user:5");
    }
}
