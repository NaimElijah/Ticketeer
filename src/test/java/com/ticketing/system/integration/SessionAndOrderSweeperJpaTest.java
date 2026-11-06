package com.ticketing.system.integration;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doAnswer;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.ticketing.system.bootstrap.scheduling.SessionAndOrderSweeper;
import com.ticketing.system.catalog.application.dto.InventorySelectionDTO;
import com.ticketing.system.catalog.application.port.out.EventRepository;
import com.ticketing.system.catalog.application.service.CatalogService;
import com.ticketing.system.catalog.application.service.EventManagementService;
import com.ticketing.system.governance.application.service.SystemAdminService;
import com.ticketing.system.identity.application.service.AuthenticationService;
import com.ticketing.system.integration.SeatedShowFixture.Show;
import com.ticketing.system.organization.application.service.CompanyManagementService;
import com.ticketing.system.sales.application.port.out.ActiveOrderRepository;
import com.ticketing.system.sales.application.service.ReservationService;
import com.ticketing.system.sales.domain.ActiveOrder;
import com.ticketing.system.sales.domain.CartLineItem;
import com.ticketing.system.shared.dto.AuthTokenDTO;

/**
 * UC-2, the cart-expiry sweep, on the Jpa adapters over H2. {@code SessionAndOrderSweeperTest} drives the
 * sweeper over doubles; here its tick runs in its own {@code @Transactional}, and the release, the delete and
 * the {@code @Version} check that guards them are the database's. On JPA the order lock is a no-op and the
 * sweeper re-checks the copy its scan loaded, so a buyer who changes the cart during the tick is caught only
 * by the order's {@code @Version} when the tick deletes it: the tick must then roll back whole. The sweeper is
 * no application service, so the conflict reaches it raw, untranslated by
 * {@code OptimisticLockTranslationAspect}.
 */
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext // its bean overrides give this class a context no other class reuses
class SessionAndOrderSweeperJpaTest {

    private static final String FIRST_SEAT = SeatedShowFixture.SEATS.get(0);
    private static final String SECOND_SEAT = SeatedShowFixture.SEATS.get(1);
    private static final long AWAIT_SECONDS = 20;

    @Autowired private AuthenticationService authService;
    @Autowired private CompanyManagementService companyService;
    @Autowired private EventManagementService eventManagementService;
    @Autowired private CatalogService catalogService;
    @Autowired private SystemAdminService systemAdminService;
    @Autowired private ReservationService reservationService;
    @Autowired private ActiveOrderRepository activeOrderRepository;
    @Autowired private SessionAndOrderSweeper sweeper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private Clock clock;

    // The Jpa adapter, spied on only to let the buyer change the cart while the tick holds it.
    @MockitoSpyBean private EventRepository eventRepository;

    private SeatedShowFixture fixture;
    private Show show;
    private ExecutorService buyerTab;

    @BeforeEach
    void publishShow() {
        fixture = new SeatedShowFixture(authService, companyService, eventManagementService, catalogService,
                systemAdminService, clock);
        fixture.ensureMarketOpen();
        show = fixture.publishSeatedShow();
        buyerTab = Executors.newSingleThreadExecutor();
    }

    @AfterEach
    void stopBuyerTab() {
        buyerTab.shutdownNow();
    }

    @Test
    void GivenAnExpiredCart_WhenSweep_ThenItsSeatIsBackInStockAndTheCartIsGone() {
        AuthTokenDTO member = fixture.registerAndLogin("sweptMember");
        reservationService.reserveForMember(member.token(), show.eventId(), show.zoneId(), seat(FIRST_SEAT));
        letTheHoldsRunOut(member);

        sweeper.sweep();

        assertAll(
                () -> assertEquals(SeatedShowFixture.SEATS.size(), fixture.availableSeats(show),
                        "the expired hold must be back in stock"),
                () -> assertEquals(0, fixture.zoneOf(show).getReservedAmount(), "no seat may stay held"),
                () -> assertNull(activeOrderRepository.getByUserId(member.userId()), "the expired cart must be gone"));
    }

    @Test
    void GivenTheBuyerReservesAnotherSeatDuringTheTick_WhenSweep_ThenTheTickRollsBackAndTheCartKeepsBothSeats() {
        AuthTokenDTO member = fixture.registerAndLogin("sweepRaceMember");
        reservationService.reserveForMember(member.token(), show.eventId(), show.zoneId(), seat(FIRST_SEAT));
        letTheHoldsRunOut(member);
        AtomicReference<Throwable> buyerFailure = reserveWhenTheTickLocksTheEvent(
                () -> reservationService.reserveForMember(member.token(), show.eventId(), show.zoneId(),
                        seat(SECOND_SEAT)));

        assertThrows(OptimisticLockingFailureException.class, sweeper::sweep,
                "the tick deletes the copy of the cart it loaded before the buyer's change, so it must fail");

        assertNull(buyerFailure.get(), "arrangement: the buyer's reservation must commit during the tick");
        ActiveOrder cart = activeOrderRepository.getByUserId(member.userId());
        assertNotNull(cart, "the rolled-back tick must leave the cart");
        assertAll(
                () -> assertEquals(List.of(FIRST_SEAT, SECOND_SEAT), sortedSeats(cart),
                        "the cart must keep the expired line and the one the buyer just added"),
                () -> assertEquals(SeatedShowFixture.SEATS.size() - 2, fixture.availableSeats(show),
                        "the tick's release of " + FIRST_SEAT + " must roll back with it, and "
                                + SECOND_SEAT + " stays held"));
    }

    // Moves every line of the member's cart back past the hold, as if the clock had run on; a direct write,
    // so the cart's @Version is left as it was.
    private void letTheHoldsRunOut(AuthTokenDTO member) {
        String orderKey = activeOrderRepository.getByUserId(member.userId()).getOrderKey();
        LocalDateTime pastTheHold = LocalDateTime.now(clock).minus(CartLineItem.getExpirationLimit()).minusMinutes(1);
        jdbc.update("update active_order_items set added_at = ? where order_key = ?", pastTheHold, orderKey);
    }

    // The tick takes the event lock after it has re-checked the cart it loaded, and before it releases or
    // deletes anything; the buyer's reservation runs to its commit there. Its outcome is returned rather than
    // thrown, so a broken arrangement cannot pass for the tick's own failure.
    private AtomicReference<Throwable> reserveWhenTheTickLocksTheEvent(Runnable reservation) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean ran = new AtomicBoolean(false);
        doAnswer(invocation -> {
            if (ran.compareAndSet(false, true)) {
                try {
                    buyerTab.submit(reservation).get(AWAIT_SECONDS, TimeUnit.SECONDS);
                } catch (ExecutionException e) {
                    failure.set(e.getCause());
                } catch (Exception e) {
                    failure.set(e);
                }
            }
            return invocation.callRealMethod();
        }).when(eventRepository).lockForUpdate(show.eventId());
        return failure;
    }

    private static List<String> sortedSeats(ActiveOrder order) {
        return order.getItems().stream().map(CartLineItem::getSeatNumber).sorted().toList();
    }

    private static InventorySelectionDTO seat(String seat) {
        return InventorySelectionDTO.seated(List.of(seat));
    }
}
