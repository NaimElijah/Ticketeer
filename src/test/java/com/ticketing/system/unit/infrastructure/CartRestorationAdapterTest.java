package com.ticketing.system.unit.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import com.ticketing.system.sales.adapter.out.identity.CartRestorationAdapter;
import com.ticketing.system.sales.application.port.out.ActiveOrderRepository;
import com.ticketing.system.sales.application.service.ReservationService;
import com.ticketing.system.sales.domain.ActiveOrder;
import com.ticketing.system.shared.dto.ActiveOrderDTO;

/**
 * Unit tests for {@link CartRestorationAdapter}, the sales-side implementation of identity's
 * {@code CartRestorationPort}. These cover the D9a guest-cart-merge / orphaned-member-cart-restore
 * behavior that previously lived in {@code AuthenticationService.handleCartOnPromotion} and was
 * moved here verbatim during the identity&rarr;sales dependency inversion.
 */
class CartRestorationAdapterTest {

    private ReservationService mockReservation;      // sales service the adapter delegates restore to
    private ActiveOrderRepository mockActiveOrderRepo; // sales cart port the adapter drives for the merge
    private CartRestorationAdapter adapter;          // subject under test

    /** Builds fresh mocks and the adapter before each test. */
    @BeforeEach
    void setUp() {
        mockReservation = mock(ReservationService.class);         // stub restore delegation
        mockActiveOrderRepo = mock(ActiveOrderRepository.class);  // stub cart lookups/saves
        adapter = new CartRestorationAdapter(mockReservation, mockActiveOrderRepo);
    }

    // ----------------------------------------------------------------------
    // restoreActiveOrder — pure delegation to the sales ReservationService
    // ----------------------------------------------------------------------

    /** The adapter returns exactly what the sales service produces, unmodified. */
    @Test
    void restoreActiveOrder_delegatesToReservationService() {
        ActiveOrderDTO restored = new ActiveOrderDTO(7, null, null, 0L, 0.0, List.of());
        when(mockReservation.restoreActiveOrder(7)).thenReturn(restored);

        assertSame(restored, adapter.restoreActiveOrder(7)); // pass-through, no re-wrapping
        verify(mockReservation, times(1)).restoreActiveOrder(7);
    }

    // ----------------------------------------------------------------------
    // mergeGuestCartOnPromotion — D9a cart wiring (relocated from AuthenticationService)
    // ----------------------------------------------------------------------

    /** Case 1: a guest cart bound to the session is claimed for the now-authenticated member. */
    @Test
    void mergeGuestCartOnPromotion_whenGuestCartExists_promotesItToMember() {
        ActiveOrder guestCart = ActiveOrder.forGuest("preserved-sid"); // real guest cart
        when(mockActiveOrderRepo.getBySessionId("preserved-sid")).thenReturn(Optional.of(guestCart));

        adapter.mergeGuestCartOnPromotion("preserved-sid", 7);

        // Cart promoted in place — userId now set, sessionId preserved.
        assertTrue(guestCart.isMember());
        assertEquals(7, guestCart.getUserId());
        assertEquals("preserved-sid", guestCart.getSessionId());
        verify(mockActiveOrderRepo, times(1)).save(guestCart);
        // Guest cart wins — the orphan-member-cart restoration path is not taken.
        verify(mockActiveOrderRepo, never()).getByUserId(anyInt());
    }

    /** Case 2: no guest cart, but an orphaned member cart is re-attached to the new session. */
    @Test
    void mergeGuestCartOnPromotion_whenNoGuestCartButOrphanedMemberCartExists_restoresIt() {
        when(mockActiveOrderRepo.getBySessionId("new-sid")).thenReturn(Optional.empty());
        ActiveOrder priorCart = ActiveOrder.forMember(7, "old-stale-sid"); // stale-session member cart
        when(mockActiveOrderRepo.getByUserId(7)).thenReturn(priorCart);

        adapter.mergeGuestCartOnPromotion("new-sid", 7);

        // Prior cart's sessionId rebound to the fresh session.
        assertEquals("new-sid", priorCart.getSessionId());
        assertEquals(7, priorCart.getUserId());
        verify(mockActiveOrderRepo, times(1)).save(priorCart);
    }

    /** Case 3: no cart at all — the merge is a no-op on the repository (no save). */
    @Test
    void mergeGuestCartOnPromotion_whenNoCartAtAll_isNoOp() {
        when(mockActiveOrderRepo.getBySessionId("sid-1")).thenReturn(Optional.empty());
        when(mockActiveOrderRepo.getByUserId(7)).thenReturn(null);

        adapter.mergeGuestCartOnPromotion("sid-1", 7);

        verify(mockActiveOrderRepo, never()).save(any());
    }

    /**
     * Lock discipline: both cart keys are acquired in lexicographic order and released in reverse,
     * matching ReservationService / the sweeper — the deadlock-avoidance invariant preserved from
     * the original private method.
     */
    @Test
    void mergeGuestCartOnPromotion_locksAndUnlocksBothKeysInCanonicalOrder() {
        when(mockActiveOrderRepo.getBySessionId("sid-1")).thenReturn(Optional.empty());
        when(mockActiveOrderRepo.getByUserId(7)).thenReturn(null);

        adapter.mergeGuestCartOnPromotion("sid-1", 7);

        // "sess:sid-1" < "user:7" lexicographically, so guest key is locked first, unlocked last.
        InOrder ordered = inOrder(mockActiveOrderRepo);
        ordered.verify(mockActiveOrderRepo).lockForUpdate("sess:sid-1");
        ordered.verify(mockActiveOrderRepo).lockForUpdate("user:7");
        ordered.verify(mockActiveOrderRepo).unlock("user:7");
        ordered.verify(mockActiveOrderRepo).unlock("sess:sid-1");
    }
}
