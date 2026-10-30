package com.ticketing.system.integration;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.ticketing.system.catalog.application.dto.InventorySelectionDTO;
import com.ticketing.system.catalog.application.port.in.InventoryCommandPort;
import com.ticketing.system.catalog.application.service.CatalogService;
import com.ticketing.system.catalog.application.service.EventManagementService;
import com.ticketing.system.governance.application.service.SystemAdminService;
import com.ticketing.system.identity.application.service.AuthenticationService;
import com.ticketing.system.integration.SeatedShowFixture.Show;
import com.ticketing.system.organization.application.service.CompanyManagementService;
import com.ticketing.system.sales.application.port.out.OrderReceiptRepository;
import com.ticketing.system.sales.application.service.CheckoutService;
import com.ticketing.system.sales.application.service.ReservationService;
import com.ticketing.system.sales.domain.OrderReceipt;
import com.ticketing.system.shared.dto.AuthTokenDTO;
import com.ticketing.system.shared.dto.CardDetailsDTO;

/**
 * UC-19 cancellation against UC-15 checkout (SLR.1): a cancel that lands while a checkout's Phase 3 is
 * committing the sale must refund that sale. The cancel commits CANCELED, then refunds the receipts it
 * can see. Phase 3 checks that the event is still on sale and commits the receipt in one transaction,
 * so unless the cancel waits for it, the cancel's refund misses the receipt, and the buyer is charged
 * for a cancelled event and never refunded.
 *
 * <p>The checkout is held inside Phase 3, just after its on-sale check, until the cancel has either
 * finished or is waiting on a lock. Runs on the Jpa adapters over H2; {@code PostgresCancelDuringCheckoutIT} runs
 * it on PostgreSQL.
 */
@SpringBootTest
@ActiveProfiles("test")
class CancelDuringCheckoutJpaTest {

    private static final String SEAT = SeatedShowFixture.SEATS.get(0);
    private static final String CURRENCY = "ILS";
    private static final CardDetailsDTO CARD = new CardDetailsDTO("4111111111111234", "123", 12, 2030, "Test Holder");
    private static final long AWAIT_SECONDS = 20;

    @Autowired private AuthenticationService authService;
    @Autowired private CompanyManagementService companyService;
    @Autowired private EventManagementService eventManagementService;
    @Autowired private CatalogService catalogService;
    @Autowired private SystemAdminService systemAdminService;
    @Autowired private ReservationService reservationService;
    @Autowired private CheckoutService checkoutService;
    @Autowired private OrderReceiptRepository orderReceiptRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private Clock clock;

    // The catalog port, spied on only to hold the checkout inside Phase 3 after its on-sale check.
    @MockitoSpyBean private InventoryCommandPort inventoryPort;

    private SeatedShowFixture fixture;

    @BeforeEach
    void openMarket() {
        fixture = new SeatedShowFixture(authService, companyService, eventManagementService, catalogService,
                systemAdminService, clock);
        fixture.ensureMarketOpen();
    }

    @Test
    void GivenACheckoutCommittingItsSale_WhenTheEventIsCancelledMeanwhile_ThenTheSaleIsRefunded() throws Exception {
        Show show = fixture.publishSeatedShow();
        AuthTokenDTO member = fixture.registerAndLogin("cancelRace");
        reservationService.reserveForMember(member.token(), show.eventId(), show.zoneId(),
                InventorySelectionDTO.seated(List.of(SEAT)));
        CountDownLatch inPhase3 = new CountDownLatch(1);
        CountDownLatch releasePhase3 = new CountDownLatch(1);
        holdTheFirstOnSaleCheck(inPhase3, releasePhase3);
        ExecutorService threads = Executors.newCachedThreadPool();
        try {
            Future<?> checkout = threads.submit(() -> checkoutService.checkoutMember(
                    member.token(), "cancel-race-" + UUID.randomUUID(), CURRENCY, CARD));
            awaitOrFail(inPhase3, "arrangement: the checkout reaches Phase 3");
            Future<?> cancel = threads.submit(() -> {
                eventManagementService.cancelEventAndRefund(show.ownerToken(), show.eventId());
                return null;
            });
            await().atMost(Duration.ofSeconds(AWAIT_SECONDS))
                    .until(() -> cancel.isDone() || aSessionWaitsOnALock());
            releasePhase3.countDown();

            Throwable checkoutFailure = failureOf(() -> checkout.get(AWAIT_SECONDS, TimeUnit.SECONDS));
            Throwable cancelFailure = failureOf(() -> cancel.get(AWAIT_SECONDS, TimeUnit.SECONDS));

            assertNull(cancelFailure, "the cancel succeeds");
            assertNull(checkoutFailure, "the checkout's sale commits before the cancel");
            List<OrderReceipt> receipts = orderReceiptRepository.findByEventId(show.eventId());
            assertAll(
                    () -> assertEquals(1, receipts.size(), "the sale's receipt"),
                    () -> assertTrue(receipts.stream().allMatch(OrderReceipt::isFullyRefunded),
                            "a sale on a cancelled event must be refunded by the cancel"));
        } finally {
            releasePhase3.countDown();
            threads.shutdownNow();
        }
    }

    private void holdTheFirstOnSaleCheck(CountDownLatch inPhase3, CountDownLatch releasePhase3) {
        AtomicBoolean first = new AtomicBoolean(true);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            if (first.getAndSet(false)) {
                inPhase3.countDown();
                awaitOrFail(releasePhase3, "the test releases the held checkout");
            }
            return null;
        }).when(inventoryPort).validateEventsOnSale(anyList());
    }

    /** Whether some connection is waiting for a lock another holds, as H2 reports it. */
    protected boolean aSessionWaitsOnALock() {
        return count("select count(*) from information_schema.sessions where blocker_id is not null") > 0;
    }

    protected int count(String sql) {
        Integer count = jdbc.queryForObject(sql, Integer.class);
        return count == null ? 0 : count;
    }

    /** What the call threw, unwrapped from a Future's ExecutionException; {@code null} when it returned. */
    private static Throwable failureOf(Callable<?> call) {
        try {
            call.call();
            return null;
        } catch (ExecutionException e) {
            return e.getCause();
        } catch (Exception e) {
            return e;
        }
    }

    private static void awaitOrFail(CountDownLatch latch, String what) throws InterruptedException {
        if (!latch.await(AWAIT_SECONDS, TimeUnit.SECONDS)) {
            throw new IllegalStateException(what + ", but it never did");
        }
    }
}
