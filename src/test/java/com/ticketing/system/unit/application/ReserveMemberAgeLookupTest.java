package com.ticketing.system.unit.application;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessResourceFailureException;

import com.ticketing.system.catalog.application.dto.InventorySelectionDTO;
import com.ticketing.system.catalog.application.port.in.InventoryCommandPort;
import com.ticketing.system.identity.application.port.out.SessionManager;
import com.ticketing.system.identity.application.port.out.UserRepository;
import com.ticketing.system.identity.domain.User;
import com.ticketing.system.sales.application.port.out.ActiveOrderRepository;
import com.ticketing.system.sales.application.port.out.MarketGate;
import com.ticketing.system.sales.application.service.ReservationService;
import com.ticketing.system.shared.exception.UserNotFoundException;
import com.ticketing.system.shared.metrics.ISystemMetrics;

/**
 * A member's reservation passes the member's age to the inventory port, which checks it against the
 * event's age policy at the reserve stage (#187). An unknown age passes that stage, so a failure to
 * read the member must fail the reservation, never stand in for "age unknown".
 *
 * <p>The inventory port is mocked: it lets an unknown age through, as the reserve stage of a real
 * 18+ event does, and records the age it was given.
 */
class ReserveMemberAgeLookupTest {

    private static final Instant T0 = Instant.parse("2027-07-15T17:00:00Z");
    private static final String MEMBER_TOKEN = "header.member-claims.signature";
    private static final int USER_ID = 7;
    private static final int EVENT_ID = 10;
    private static final int ZONE_ID = 5;
    private static final int MINOR_AGE = 17;
    private static final double PRICE = 50.0;

    private final InventoryCommandPort inventoryPort = mock(InventoryCommandPort.class);
    private final ActiveOrderRepository activeOrderRepository = mock(ActiveOrderRepository.class);
    private final SessionManager sessionManager = mock(SessionManager.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final MarketGate marketGate = mock(MarketGate.class);
    private final ReservationService reservationService = new ReservationService(
            inventoryPort,
            activeOrderRepository,
            sessionManager,
            mock(ApplicationEventPublisher.class),
            userRepository,
            marketGate,
            mock(ISystemMetrics.class),
            Clock.fixed(T0, ZoneOffset.UTC));

    @BeforeEach
    void liveMemberOnAnOpenMarket() {
        when(marketGate.isOpen()).thenReturn(true);
        when(sessionManager.validateToken(MEMBER_TOKEN)).thenReturn(true);
        when(sessionManager.extractUserId(MEMBER_TOKEN)).thenReturn(USER_ID);
        when(inventoryPort.reserve(anyInt(), anyInt(), any(), anyString(), any(), anyInt(), any(), anyInt()))
                .thenReturn(PRICE);
    }

    @Test
    void GivenTheUserStoreDown_WhenMemberReserves_ThenTheStoreFailurePropagatesAndNothingIsHeld() {
        DataAccessResourceFailureException storeDown = new DataAccessResourceFailureException("database unreachable");
        when(userRepository.getUserById(USER_ID)).thenThrow(storeDown);

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> reservationService.reserveForMember(
                MEMBER_TOKEN, EVENT_ID, ZONE_ID, InventorySelectionDTO.standing(1)));

        assertSame(storeDown, thrown,
                "a member whose age cannot be read must not reserve as a buyer of unknown age");
        verify(inventoryPort, never()).reserve(anyInt(), anyInt(), any(), anyString(), any(), anyInt(), any(), anyInt());
        verify(activeOrderRepository, never()).save(any());
    }

    // A live token whose member has no user row is no buyer of unknown age: the reservation is refused.
    @Test
    void GivenMemberWithNoUserRow_WhenMemberReserves_ThenRefusedAsUserNotFoundAndNothingIsHeld() {
        UserNotFoundException noSuchUser = new UserNotFoundException(USER_ID);
        when(userRepository.getUserById(USER_ID)).thenThrow(noSuchUser);

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> reservationService.reserveForMember(
                MEMBER_TOKEN, EVENT_ID, ZONE_ID, InventorySelectionDTO.standing(1)));

        assertSame(noSuchUser, thrown, "a member with no user row must be refused, not reserve with an unknown age");
        verify(inventoryPort, never()).reserve(anyInt(), anyInt(), any(), anyString(), any(), anyInt(), any(), anyInt());
        verify(activeOrderRepository, never()).save(any());
    }

    // Guard: a member whose age is read reserves with that age, for the port's age policy to judge.
    @Test
    void GivenMemberWithKnownAge_WhenMemberReserves_ThenThePortJudgesThatAge() {
        when(userRepository.getUserById(USER_ID)).thenReturn(
                new User(USER_ID, "minor", "minor@example.test", "unused-password-hash", MINOR_AGE));

        reservationService.reserveForMember(MEMBER_TOKEN, EVENT_ID, ZONE_ID, InventorySelectionDTO.standing(1));

        verify(inventoryPort).reserve(eq(EVENT_ID), eq(ZONE_ID), any(), anyString(), any(), eq(USER_ID),
                eq(MINOR_AGE), anyInt());
    }
}
