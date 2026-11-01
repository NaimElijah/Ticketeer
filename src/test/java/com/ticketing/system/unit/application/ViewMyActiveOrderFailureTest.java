package com.ticketing.system.unit.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessResourceFailureException;

import com.ticketing.system.catalog.application.port.in.InventoryCommandPort;
import com.ticketing.system.identity.application.port.out.SessionManager;
import com.ticketing.system.identity.application.port.out.UserRepository;
import com.ticketing.system.sales.application.port.out.ActiveOrderRepository;
import com.ticketing.system.sales.application.port.out.MarketGate;
import com.ticketing.system.sales.application.service.ReservationService;
import com.ticketing.system.sales.domain.ActiveOrder;
import com.ticketing.system.shared.dto.ActiveOrderDTO;
import com.ticketing.system.shared.exception.InvalidTokenException;
import com.ticketing.system.shared.exception.SessionExpiredException;
import com.ticketing.system.shared.metrics.ISystemMetrics;

/**
 * {@link ReservationService#viewMyActiveOrder} tries the credential as a member token first and falls
 * back to the guest path only when it is not a live member token (#187). A live member whose cart
 * cannot be read is not a guest: the store's failure must reach the caller, rather than the guest path's
 * refusal of the member's token, which the UI shows as an expired session.
 */
class ViewMyActiveOrderFailureTest {

    private static final Instant T0 = Instant.parse("2027-07-15T17:00:00Z");
    private static final LocalDateTime NOW = LocalDateTime.ofInstant(T0, ZoneOffset.UTC);
    private static final String MEMBER_TOKEN = "header.member-claims.signature";
    private static final String GUEST_SESSION_ID = "guest-session-1";
    private static final int USER_ID = 7;
    private static final int EVENT_ID = 10;
    private static final int ZONE_ID = 5;
    private static final double PRICE = 50.0;

    private final ActiveOrderRepository activeOrderRepository = mock(ActiveOrderRepository.class);
    private final SessionManager sessionManager = mock(SessionManager.class);
    private final ReservationService reservationService = new ReservationService(
            mock(InventoryCommandPort.class),
            activeOrderRepository,
            sessionManager,
            mock(ApplicationEventPublisher.class),
            mock(UserRepository.class),
            mock(MarketGate.class),
            mock(ISystemMetrics.class),
            Clock.fixed(T0, ZoneOffset.UTC));

    @Test
    void GivenLiveMemberTokenAndTheCartStoreDown_WhenViewMyActiveOrder_ThenTheStoreFailurePropagates() {
        when(sessionManager.validateToken(MEMBER_TOKEN)).thenReturn(true);
        when(sessionManager.extractUserId(MEMBER_TOKEN)).thenReturn(USER_ID);
        DataAccessResourceFailureException storeDown = new DataAccessResourceFailureException("database unreachable");
        when(activeOrderRepository.getByUserId(USER_ID)).thenThrow(storeDown);

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> reservationService.viewMyActiveOrder(MEMBER_TOKEN));

        assertSame(storeDown, thrown,
                "a live member whose cart cannot be read must see the store's failure, not be refused as a guest");
    }

    // The token check itself reads the session store: its failure says nothing about whether the credential
    // is a member's, so it must not be taken for "not a member token" and handed to the guest path.
    @Test
    void GivenTheSessionStoreDownDuringTheTokenCheck_WhenViewMyActiveOrder_ThenTheStoreFailurePropagates() {
        DataAccessResourceFailureException storeDown = new DataAccessResourceFailureException("database unreachable");
        when(sessionManager.validateToken(MEMBER_TOKEN)).thenThrow(storeDown);

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> reservationService.viewMyActiveOrder(MEMBER_TOKEN));

        assertSame(storeDown, thrown, "a session-store failure must reach the caller, not fall back to the guest path");
        verify(sessionManager, never()).validateGuestSession(MEMBER_TOKEN);
    }

    // Guard: a guest's raw session id is no JWT, so the member check rejects it and the guest path runs.
    @Test
    void GivenAGuestsSessionIdThatIsNoMemberToken_WhenViewMyActiveOrder_ThenTheGuestsCartIsShown() {
        when(sessionManager.validateToken(GUEST_SESSION_ID)).thenThrow(new InvalidTokenException("malformed token"));
        when(sessionManager.validateGuestSession(GUEST_SESSION_ID)).thenReturn(true);
        ActiveOrder guestCart = ActiveOrder.forGuest(GUEST_SESSION_ID, NOW);
        guestCart.addStandingReservation(EVENT_ID, ZONE_ID, 1, PRICE, NOW);
        when(activeOrderRepository.getBySessionId(GUEST_SESSION_ID)).thenReturn(Optional.of(guestCart));

        ActiveOrderDTO shown = reservationService.viewMyActiveOrder(GUEST_SESSION_ID);

        assertNotNull(shown, "a live guest's cart must be shown");
        assertEquals(GUEST_SESSION_ID, shown.sessionId(), "the cart shown must be the guest's");
        assertEquals(1, shown.lines().size(), "the guest's line must be shown");
    }

    // Guard: a dead member token falls back to the guest path, which refuses it as no live guest session.
    @Test
    void GivenAnExpiredMemberToken_WhenViewMyActiveOrder_ThenRefusedAsAnExpiredSessionWithoutReadingAMemberCart() {
        when(sessionManager.validateToken(MEMBER_TOKEN)).thenThrow(new SessionExpiredException());

        assertThrows(SessionExpiredException.class, () -> reservationService.viewMyActiveOrder(MEMBER_TOKEN));

        verify(activeOrderRepository, never()).getByUserId(anyInt());
    }
}
