package com.ticketing.system.unit.application;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

import ch.qos.logback.classic.Level;

import com.ticketing.system.testutil.CompanyRepositories;
import com.ticketing.system.shared.dto.BarcodeDTO;
import com.ticketing.system.testutil.LogCapture;
import com.ticketing.system.testutil.TestTransactions;
import com.ticketing.system.testutil.TestTransactions.RecordingTransactionManager;
import com.ticketing.system.shared.dto.CardDetailsDTO;
import com.ticketing.system.shared.dto.CheckoutResultDTO;
import com.ticketing.system.shared.dto.IssuanceRequestDTO;
import com.ticketing.system.shared.dto.IssuanceResultDTO;
import com.ticketing.system.shared.dto.PaymentRequestDTO;
import com.ticketing.system.shared.dto.PaymentResultDTO;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import com.ticketing.system.sales.application.port.out.PaymentGateway;
import com.ticketing.system.shared.event.PurchaseFailedNotice;
import com.ticketing.system.identity.application.port.out.SessionManager;
import com.ticketing.system.sales.application.port.out.TicketIssuer;
import com.ticketing.system.sales.application.service.CheckoutService;
import com.ticketing.system.sales.application.port.out.MarketGate;
import com.ticketing.system.shared.exception.AuthenticationFailedException;
import com.ticketing.system.shared.exception.CheckoutInProgressException;
import com.ticketing.system.shared.exception.ConcurrentReservationException;
import com.ticketing.system.shared.exception.EntityNotFoundException;
import com.ticketing.system.shared.exception.EventNotFoundException;
import com.ticketing.system.shared.exception.ExternalServiceUnavailableException;
import com.ticketing.system.shared.exception.IdempotencyConflictException;
import com.ticketing.system.shared.exception.InvalidStateTransitionException;
import com.ticketing.system.shared.exception.InvalidTokenException;
import com.ticketing.system.shared.exception.MarketNotOpenException;
import com.ticketing.system.shared.exception.PaymentGatewayException;
import com.ticketing.system.shared.exception.PaymentGatewayUnreachableException;
import com.ticketing.system.shared.exception.RefundFailedException;
import com.ticketing.system.shared.exception.SessionExpiredException;
import com.ticketing.system.shared.exception.TicketIssuanceFailedException;
import com.ticketing.system.shared.exception.TicketIssuerUnreachableException;
import com.ticketing.system.sales.domain.ActiveOrder;
import com.ticketing.system.sales.domain.CartLineItem;
import com.ticketing.system.sales.application.port.out.ActiveOrderRepository;
import com.ticketing.system.sales.application.port.out.TicketRepository;
import com.ticketing.system.sales.domain.Ticket;
import com.ticketing.system.catalog.domain.DiscountPolicy;
import com.ticketing.system.catalog.domain.Event;
import com.ticketing.system.catalog.domain.EventCategory;
import com.ticketing.system.catalog.domain.EventStatus;
import com.ticketing.system.catalog.application.port.out.EventRepository;
import com.ticketing.system.catalog.adapter.out.persistence.MemoryEventRepository;
import com.ticketing.system.catalog.application.service.InventoryService;
import com.ticketing.system.catalog.domain.InventorySelection;
import com.ticketing.system.catalog.domain.InventoryZone;
import com.ticketing.system.catalog.domain.Location;
import com.ticketing.system.catalog.domain.Seat;
import com.ticketing.system.catalog.domain.SeatStatus;
import com.ticketing.system.catalog.domain.SeatedZone;
import com.ticketing.system.catalog.domain.ShowDate;
import com.ticketing.system.catalog.domain.StandingZone;
import com.ticketing.system.catalog.domain.VenueMap;
import com.ticketing.system.sales.application.port.out.OrderReceiptRepository;
import com.ticketing.system.sales.domain.OrderReceipt;
import com.ticketing.system.sales.domain.ReceiptLine;
import com.ticketing.system.shared.domain.policy.NoPurchasePolicy;
import com.ticketing.system.shared.domain.policy.PurchasePolicy;
import com.ticketing.system.identity.application.port.out.UserRepository;
import com.ticketing.system.identity.domain.User;
import com.ticketing.system.organization.application.port.out.ProductionCompanyRepository;
import com.ticketing.system.shared.domain.policy.AgePurchasePolicy;
import com.ticketing.system.shared.domain.policy.MaxTicketsPurchasePolicy;
import com.ticketing.system.shared.domain.policy.MinTicketsPurchasePolicy;
import com.ticketing.system.shared.domain.policy.AndPurchasePolicy;
import com.ticketing.system.shared.domain.policy.OrPurchasePolicy;

class CheckoutServiceTest {

    // Holds, pricing and receipts are judged at this instant; carts below are stamped relative to it
    // as UTC wall time, the way the services read the injected clock (ADR-008).
    private static final Instant T0 = Instant.parse("2027-07-15T17:00:00Z");
    private static final LocalDateTime NOW = LocalDateTime.ofInstant(T0, ZoneOffset.UTC);
    // A discount is a fraction of a price, so prices are compared to within a hundredth of a cent.
    private static final double PRICE_TOLERANCE = 0.0001;

    private ActiveOrderRepository mockActiveOrderRepo;
    private EventRepository mockEventRepo;
    private TicketRepository mockTicketRepo;
    private OrderReceiptRepository mockOrderReceiptRepo;
    private TicketIssuer mockTicketIssuer;
    private PaymentGateway mockPaymentGateway;
    private ApplicationEventPublisher mockEventPublisher;
    private SessionManager mockiSessionManager;
    private UserRepository mockUserRepository;
    private MarketGate mockMarketGate;

    private CheckoutService checkoutService;
    // The checkout's own transactions (Phase 3), apart from the inventory service's.
    private RecordingTransactionManager checkoutTransactions;

    private final String VALID_TOKEN = "valid-token";
    private final String INVALID_TOKEN = "invalid-token";

    private final int USER_ID = 1;

    private final int EVENT_ID_1 = 1;
    private final int EVENT_ID_2 = 2;

    private final int ZONE_ID_1 = 10;
    private final int ZONE_ID_2 = 20;
    private final int ZONE_ID_3 = 30;

    private final int TICKET_ID_1 = 101;
    private final int TICKET_ID_2 = 102;

    private final int PAYMENT_TRANSACTION_ID = 201;
    private final String ISSUANCE_TRANSACTION_ID = "issue-tx-1";

    private final String IDEMPOTENCY_KEY = "idem-key-1";
    private final String CURRENCY = "ILS";
    private final CardDetailsDTO PAYMENT_METHOD_TOKEN =
            new CardDetailsDTO("4111111111111234", "123", 12, 2030, "Test Holder");

    @BeforeEach
    void setUp() {
        mockActiveOrderRepo = mock(ActiveOrderRepository.class);
        mockEventRepo = mock(EventRepository.class);
        // A sale loads its events through findForSale; each test stubs the events once, on findById.
        when(mockEventRepo.findForSale(anyInt())).thenAnswer(call -> mockEventRepo.findById(call.<Integer>getArgument(0)));
        mockTicketRepo = mock(TicketRepository.class);
        // Ticket ids are minted by the repository (#19), starting at TICKET_ID_1 for each test.
        AtomicInteger ticketIds = new AtomicInteger(TICKET_ID_1);
        when(mockTicketRepo.nextId()).thenAnswer(invocation -> ticketIds.getAndIncrement());
        
        mockOrderReceiptRepo = mock(OrderReceiptRepository.class);
        AtomicInteger receiptIds = new AtomicInteger(1);
        when(mockOrderReceiptRepo.nextId()).thenAnswer(invocation -> receiptIds.getAndIncrement());

        mockTicketIssuer = mock(TicketIssuer.class);
        mockPaymentGateway = mock(PaymentGateway.class);
        mockEventPublisher = mock(ApplicationEventPublisher.class);
        mockiSessionManager = mock(SessionManager.class);
        mockUserRepository = mock(UserRepository.class);
        User mockUser = mock(User.class);
        when(mockUser.getAge()).thenReturn(20);
          when(mockUserRepository.getUserById(USER_ID)).thenReturn(mockUser); 
        

        mockMarketGate = mock(MarketGate.class);
        when(mockMarketGate.isOpen()).thenReturn(true);

        // Catalog owns inventory mutation behind InventoryCommandPort. Wire the REAL InventoryService
        // onto the SAME mock event store (and a mock company port for policy), so the real events each
        // test stubs on findById drive confirm/validate/price/policy one layer down. CheckoutService
        // keeps mockEventRepo for the Phase-3 buyer-lock.
        checkoutTransactions = TestTransactions.recordingManager();
        checkoutService = checkoutServiceOn(mockEventRepo);

        when(mockiSessionManager.validateToken(VALID_TOKEN)).thenReturn(true);
        when(mockiSessionManager.extractUserId(VALID_TOKEN)).thenReturn(USER_ID);
    }

    // A checkout service whose inventory and buyer locks both use the given event store.
    private CheckoutService checkoutServiceOn(EventRepository events) {
        return new CheckoutService(
                mockActiveOrderRepo,
                events,
                new InventoryService(events, CompanyRepositories.everyCompanyWithoutAPolicy(),
                        TestTransactions.recordingManager()),
                mockTicketRepo,
                mockOrderReceiptRepo,
                mockTicketIssuer,
                mockPaymentGateway,
                mockEventPublisher,
                mockiSessionManager,
                mockUserRepository,
                mockMarketGate,
                checkoutTransactions,
                Clock.fixed(T0, ZoneOffset.UTC)
        );
    }

    // --- UC-32 / I.2.1: sales gated on an OPEN market ---

    @Test
    void GivenMarketClosed_WhenCheckoutMember_ThenThrowsAndNoCharge() {
        // Events are ON_SALE (see setUp) and the order is valid — the closed market
        // is the only thing blocking the sale, and it blocks before any charge.
        when(mockMarketGate.isOpen()).thenReturn(false);
        assertThrows(MarketNotOpenException.class, () ->
                checkoutService.checkoutMember(VALID_TOKEN, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN));
        verify(mockPaymentGateway, never()).charge(any());
    }

    @Test
    void GivenMarketClosed_WhenCheckoutGuest_ThenThrowsAndNoCharge() {
        when(mockMarketGate.isOpen()).thenReturn(false);
        assertThrows(MarketNotOpenException.class, () ->
                checkoutService.checkoutGuest("guest-session", "guest@test.com",
                        IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN, 30));
        verify(mockPaymentGateway, never()).charge(any());
    }

    // checkoutMember wraps every refusal in its generic checkout failure, so each refusal below asserts its
    // reason as the cause: a bare assertThrows(RuntimeException) would pass on any later failure instead.
    @Test
    void GivenMissingToken_WhenCheckout_ThenRefusedAsAnInvalidTokenAndNothingCharged() {
        RuntimeException failure = assertThrows(RuntimeException.class, () ->
                checkoutService.checkoutMember("", IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN)
        );

        assertRefusedBeforeAnyCharge(failure, InvalidTokenException.class);
    }

    @Test
    void GivenNullToken_WhenCheckout_ThenRefusedAsAnInvalidTokenAndNothingCharged() {
        RuntimeException failure = assertThrows(RuntimeException.class, () ->
                checkoutService.checkoutMember(null, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN)
        );

        assertRefusedBeforeAnyCharge(failure, InvalidTokenException.class);
    }

    // SessionManager answers false only for a null or blank token, which checkout refuses before asking, and
    // throws for a revoked or expired one. This pins checkout's defence against an adapter that answers false
    // for a token that still names a user, as the null-answer tests pin the gateway's and issuer's.
    @Test
    void GivenSessionManagerAnswersFalse_WhenCheckout_ThenAuthenticationFailsAndNothingCharged() {
        when(mockiSessionManager.validateToken(INVALID_TOKEN)).thenReturn(false);
        when(mockiSessionManager.extractUserId(INVALID_TOKEN)).thenReturn(USER_ID);

        RuntimeException failure = assertThrows(RuntimeException.class, () ->
                checkoutService.checkoutMember(INVALID_TOKEN, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN)
        );

        assertRefusedBeforeAnyCharge(failure, AuthenticationFailedException.class);
    }

    // #187: a live token that names no user is a failed sign-in, not a missing user, so the refusal
    // says nothing about which user ids exist. checkoutMember wraps every pre-flight failure in its
    // generic checkout failure, so the reason is the cause.
    @Test
    void GivenValidTokenNamingUserIdZero_WhenCheckoutMember_ThenAuthenticationFailsAndNothingIsCharged() {
        when(mockiSessionManager.extractUserId(VALID_TOKEN)).thenReturn(0);

        RuntimeException failed = assertThrows(RuntimeException.class, () ->
                checkoutService.checkoutMember(VALID_TOKEN, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN));

        assertInstanceOf(AuthenticationFailedException.class, failed.getCause(),
                "a token naming user id 0 must fail authentication, not report a missing user");
        verify(mockPaymentGateway, never()).charge(any());
    }

    @Test
    void GivenNoActiveOrder_WhenCheckout_ThenRefusedAsNotFoundAndNothingCharged() {
        when(mockActiveOrderRepo.getByUserId(USER_ID)).thenReturn(null);

        RuntimeException failure = assertThrows(RuntimeException.class, () ->
                checkoutService.checkoutMember(VALID_TOKEN, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN)
        );

        assertRefusedBeforeAnyCharge(failure, EntityNotFoundException.class);
    }

    // A refusal before anything was charged: the reason is the cause, and the gateway was never asked.
    private void assertRefusedBeforeAnyCharge(RuntimeException failure, Class<? extends Throwable> reason) {
        assertInstanceOf(reason, failure.getCause(),
                "the refusal must carry its own reason, but the cause was " + failure.getCause());
        verify(mockPaymentGateway, never()).charge(any());
    }

    //////////////////////////////////////////////////////////added test for expired order with multiple tickets scenario after check1
@Test
void GivenACartWithOneExpiredLine_WhenCheckout_ThenRefusedAndEveryHoldReleased() {
    ActiveOrder activeOrder = new ActiveOrder(USER_ID, NOW);
    String orderKey = activeOrder.getOrderKey();
    InventoryZone zone1 = new StandingZone(ZONE_ID_1, "VIP", 5, 100.0);
    InventoryZone zone2 = new StandingZone(ZONE_ID_2, "REGULAR", 5, 150.0);

    zone1.reserve(InventorySelection.standing(1, orderKey));
    zone2.reserve(InventorySelection.standing(1, orderKey));

    activeOrder.addStandingReservation(EVENT_ID_1, ZONE_ID_1, 1, 100.0, aHoldStartThatHasRunOut());
    activeOrder.addStandingReservation(EVENT_ID_1, ZONE_ID_2, 1, 150.0, NOW);

    when(mockActiveOrderRepo.getByUserId(USER_ID)).thenReturn(activeOrder);
    when(mockEventRepo.findById(EVENT_ID_1)).thenReturn(createRealEventWithZones(EVENT_ID_1, zone1, zone2));

    RuntimeException failure = assertThrows(RuntimeException.class, () ->
            checkoutService.checkoutMember(
                    VALID_TOKEN,
                    IDEMPOTENCY_KEY,
                    CURRENCY,
                    PAYMENT_METHOD_TOKEN
            )
    );

    assertRefusedBeforeAnyCharge(failure, InvalidStateTransitionException.class);
    assertEquals(0, activeOrder.countTickets(EVENT_ID_1, ZONE_ID_1, NOW));
    assertEquals(0, activeOrder.countTickets(EVENT_ID_1, ZONE_ID_2, NOW));

    assertEquals(5, zone1.getAvailableAmount());
    assertEquals(5, zone2.getAvailableAmount());
    assertEquals(0, zone1.getReservedAmount());
    assertEquals(0, zone2.getReservedAmount());
}



    @Test
void GivenMemberCartWhoseHoldHasRunOut_WhenCheckout_ThenTheHoldIsReleasedAndTheCartCleared() {
    ActiveOrder activeOrder = new ActiveOrder(USER_ID, NOW);
    String orderKey = activeOrder.getOrderKey();
    InventoryZone zone = new StandingZone(ZONE_ID_1, "VIP", 5, 100.0);
    zone.reserve(InventorySelection.standing(1, orderKey));

    activeOrder.addStandingReservation(
            EVENT_ID_1,
            ZONE_ID_1,
            1,
            100.0,
            aHoldStartThatHasRunOut()
    );

    when(mockActiveOrderRepo.getByUserId(USER_ID)).thenReturn(activeOrder);
    when(mockEventRepo.findById(EVENT_ID_1)).thenReturn(createRealEventWithZone(EVENT_ID_1, zone));

    RuntimeException failure = assertThrows(RuntimeException.class, () ->
            checkoutService.checkoutMember(
                    VALID_TOKEN,
                    IDEMPOTENCY_KEY,
                    CURRENCY,
                    PAYMENT_METHOD_TOKEN
            )
    );

    assertRefusedBeforeAnyCharge(failure, InvalidStateTransitionException.class);
    assertEquals(0, activeOrder.countTickets(EVENT_ID_1, ZONE_ID_1, NOW));
    assertEquals(5, zone.getAvailableAmount());
    assertEquals(0, zone.getReservedAmount());
}

   // PaymentGateway throws on a decline and never answers null; this pins checkout's defence against an adapter
   // that does: the answer is refused as a gateway failure, and nothing was charged to refund.
   @Test
void GivenGatewayAnswersNull_WhenCheckout_ThenRefusedAsAGatewayFailureAndTicketReturnedToStock() {
    ActiveOrder activeOrder = new ActiveOrder(USER_ID, NOW);
    String orderKey = activeOrder.getOrderKey();
    InventoryZone zone = new StandingZone(ZONE_ID_1, "VIP", 5, 100.0);
    zone.reserve(InventorySelection.standing(1, orderKey));

    activeOrder.addStandingReservation(
            EVENT_ID_1,
            ZONE_ID_1,
            1,
            100.0,
            NOW
    );

    when(mockActiveOrderRepo.getByUserId(USER_ID)).thenReturn(activeOrder);
    when(mockEventRepo.findById(EVENT_ID_1)).thenReturn(createRealEventWithZone(EVENT_ID_1, zone));

    when(mockPaymentGateway.charge(any(PaymentRequestDTO.class)))
            .thenReturn(null);

    RuntimeException failure = assertThrows(RuntimeException.class, () ->
            checkoutService.checkoutMember(
                    VALID_TOKEN,
                    IDEMPOTENCY_KEY,
                    CURRENCY,
                    PAYMENT_METHOD_TOKEN
            )
    );

    assertInstanceOf(PaymentGatewayException.class, failure.getCause(),
            "a null answer must be refused as a gateway failure, but the cause was " + failure.getCause());
    verify(mockPaymentGateway, never()).refund(anyInt(), anyDouble());
    assertEquals(0, activeOrder.countTickets(EVENT_ID_1, ZONE_ID_1, NOW));
    assertEquals(5, zone.getAvailableAmount());
    assertEquals(0, zone.getReservedAmount());
}

    // Regression for the non-atomic rollback (atomic checkout-failure rollback): on a Phase-2 payment failure
    // the inventory return must run UNDER the event lock. The in-memory event store refuses to save an event
    // its caller has not locked, so a return outside the lock fails, and the rollback then keeps the cart's
    // lines instead of clearing them.
    @Test
    void GivenPaymentFails_WhenCheckout_ThenInventoryReturnedUnderEventLock() {
        MemoryEventRepository lockCheckingEvents = new MemoryEventRepository();
        StandingZone zone = new StandingZone(ZONE_ID_1, "VIP", 5, 100.0);
        lockCheckingEvents.save(createRealEventWithZone(EVENT_ID_1, zone));   // a new event needs no lock
        ActiveOrder activeOrder = givenMemberCart();
        holdStanding(activeOrder, EVENT_ID_1, zone, 1);
        when(mockPaymentGateway.charge(any(PaymentRequestDTO.class)))
                .thenThrow(new PaymentGatewayException("payment declined by gateway"));

        assertThrows(RuntimeException.class, () -> checkoutServiceOn(lockCheckingEvents)
                .checkoutMember(VALID_TOKEN, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN));

        assertTrue(activeOrder.isEmpty(), "the rollback must reach the cart clear");
        assertEquals(5, zone.getAvailableAmount());
        assertEquals(0, zone.getReservedAmount());
    }

    // TicketIssuer throws on failure and never answers null; this pins checkout's defence against an adapter that
    // does: the answer is refused as an issuance failure, and the charge is refunded.
    @Test
void GivenIssuerAnswersNullAfterPayment_WhenCheckout_ThenClearCartAndReturnTicketsToStock() {
    ActiveOrder activeOrder = new ActiveOrder(USER_ID, NOW);
    String orderKey = activeOrder.getOrderKey();
    InventoryZone zone = new StandingZone(ZONE_ID_1, "VIP", 5, 100.0);
    zone.reserve(InventorySelection.standing(1, orderKey));

    activeOrder.addStandingReservation(
            EVENT_ID_1,
            ZONE_ID_1,
            1,
            100.0,
            NOW
    );

    PaymentResultDTO paymentResult = new PaymentResultDTO(
            PAYMENT_TRANSACTION_ID,
            "gateway",
            100.0,
            "ILS",
            NOW
    );

    when(mockActiveOrderRepo.getByUserId(USER_ID)).thenReturn(activeOrder);

    when(mockEventRepo.findById(EVENT_ID_1)).thenReturn(createRealEventWithZone(EVENT_ID_1, zone));

    when(mockPaymentGateway.charge(any(PaymentRequestDTO.class)))
            .thenReturn(paymentResult);

    when(mockTicketIssuer.issue(any(IssuanceRequestDTO.class)))
            .thenReturn(null);

    RuntimeException failure = assertThrows(RuntimeException.class, () ->
            checkoutService.checkoutMember(
                    VALID_TOKEN,
                    IDEMPOTENCY_KEY,
                    CURRENCY,
                    PAYMENT_METHOD_TOKEN
            )
    );

    assertIssuanceRefusedAndChargeRefunded(failure, 100.0);

    assertEquals(0, activeOrder.countTickets(EVENT_ID_1, ZONE_ID_1, NOW));
    assertEquals(5, zone.getAvailableAmount());
    assertEquals(0, zone.getReservedAmount());
    // Ticket ids are minted only inside the Phase-3 persistence unit (#19).
    verify(mockTicketRepo, never()).nextId();
}

   @Test
void GivenTicketIssuanceReturnsNullBarcodes_WhenCheckout_ThenClearCartAndReturnTicketsToStock() {
    ActiveOrder activeOrder = new ActiveOrder(USER_ID, NOW);
    String orderKey = activeOrder.getOrderKey();
    InventoryZone zone = new StandingZone(ZONE_ID_1, "VIP", 5, 100.0);
    zone.reserve(InventorySelection.standing(1, orderKey));

    activeOrder.addStandingReservation(
            EVENT_ID_1,
            ZONE_ID_1,
            1,
            100.0,
            NOW
    );

    PaymentResultDTO paymentResult = new PaymentResultDTO(
            PAYMENT_TRANSACTION_ID,
            "gateway",
            100.0,
            "ILS",
            NOW
    );

    IssuanceResultDTO issuanceResult = new IssuanceResultDTO(
            ISSUANCE_TRANSACTION_ID,
            "issuer",
            NOW,
            null
    );

    when(mockActiveOrderRepo.getByUserId(USER_ID)).thenReturn(activeOrder);

    when(mockEventRepo.findById(EVENT_ID_1)).thenReturn(createRealEventWithZone(EVENT_ID_1, zone));

    when(mockPaymentGateway.charge(any(PaymentRequestDTO.class)))
            .thenReturn(paymentResult);

    when(mockTicketIssuer.issue(any(IssuanceRequestDTO.class)))
            .thenReturn(issuanceResult);

    RuntimeException failure = assertThrows(RuntimeException.class, () ->
            checkoutService.checkoutMember(
                    VALID_TOKEN,
                    IDEMPOTENCY_KEY,
                    CURRENCY,
                    PAYMENT_METHOD_TOKEN
            )
    );

    assertIssuanceRefusedAndChargeRefunded(failure, 100.0);

    assertEquals(0, activeOrder.countTickets(EVENT_ID_1, ZONE_ID_1, NOW));
    assertEquals(5, zone.getAvailableAmount());
    assertEquals(0, zone.getReservedAmount());
}@Test
void GivenTicketIssuanceReturnsEmptyBarcodes_WhenCheckout_ThenClearCartAndReturnTicketsToStock() {
    ActiveOrder activeOrder = new ActiveOrder(USER_ID, NOW);
    String orderKey = activeOrder.getOrderKey();
    InventoryZone zone = new StandingZone(ZONE_ID_1, "VIP", 5, 100.0);
    zone.reserve(InventorySelection.standing(1, orderKey));

    activeOrder.addStandingReservation(
            EVENT_ID_1,
            ZONE_ID_1,
            1,
            100.0,
            NOW
    );

    PaymentResultDTO paymentResult = new PaymentResultDTO(
            PAYMENT_TRANSACTION_ID,
            "gateway",
            100.0,
            "ILS",
            NOW
    );

    IssuanceResultDTO issuanceResult = new IssuanceResultDTO(
            ISSUANCE_TRANSACTION_ID,
            "issuer",
            NOW,
            List.of()
    );

    when(mockActiveOrderRepo.getByUserId(USER_ID)).thenReturn(activeOrder);

    when(mockEventRepo.findById(EVENT_ID_1)).thenReturn(createRealEventWithZone(EVENT_ID_1, zone));

    when(mockPaymentGateway.charge(any(PaymentRequestDTO.class)))
            .thenReturn(paymentResult);

    when(mockTicketIssuer.issue(any(IssuanceRequestDTO.class)))
            .thenReturn(issuanceResult);

    RuntimeException failure = assertThrows(RuntimeException.class, () ->
            checkoutService.checkoutMember(
                    VALID_TOKEN,
                    IDEMPOTENCY_KEY,
                    CURRENCY,
                    PAYMENT_METHOD_TOKEN
            )
    );

    assertIssuanceRefusedAndChargeRefunded(failure, 100.0);

    assertEquals(0, activeOrder.countTickets(EVENT_ID_1, ZONE_ID_1, NOW));
    assertEquals(5, zone.getAvailableAmount());
    assertEquals(0, zone.getReservedAmount());
}

   @Test
void GivenMultipleTicketsButIssuerReturnsLessBarcodes_WhenCheckout_ThenClearCartAndReturnTicketsToStock() {
    ActiveOrder activeOrder = new ActiveOrder(USER_ID, NOW);
    String orderKey = activeOrder.getOrderKey();
    InventoryZone zone1 = new StandingZone(ZONE_ID_1, "VIP", 5, 100.0);
    InventoryZone zone2 = new StandingZone(ZONE_ID_2, "REGULAR", 5, 150.0);
    InventoryZone zone3 = new StandingZone(ZONE_ID_3, "BALCONY", 5, 200.0);

    zone1.reserve(InventorySelection.standing(1, orderKey));
    zone2.reserve(InventorySelection.standing(1, orderKey));
    zone3.reserve(InventorySelection.standing(1, orderKey));

    activeOrder.addStandingReservation(EVENT_ID_1, ZONE_ID_1, 1, 100.0, NOW);
    activeOrder.addStandingReservation(EVENT_ID_1, ZONE_ID_2, 1, 150.0, NOW);
    activeOrder.addStandingReservation(EVENT_ID_2, ZONE_ID_3, 1, 200.0, NOW);

    PaymentResultDTO paymentResult = new PaymentResultDTO(
            PAYMENT_TRANSACTION_ID,
            "gateway",
            450.0,
            "ILS",
            NOW
    );

    IssuanceResultDTO issuanceResult = new IssuanceResultDTO(
            ISSUANCE_TRANSACTION_ID,
            "issuer",
            NOW,
            List.of(new BarcodeDTO("barcode-1", "QR"))
    );

    when(mockActiveOrderRepo.getByUserId(USER_ID)).thenReturn(activeOrder);

    when(mockEventRepo.findById(EVENT_ID_1)).thenReturn(createRealEventWithZones(EVENT_ID_1, zone1, zone2));
    when(mockEventRepo.findById(EVENT_ID_2)).thenReturn(createRealEventWithZone(EVENT_ID_2, zone3));

    when(mockPaymentGateway.charge(any(PaymentRequestDTO.class)))
            .thenReturn(paymentResult);

    when(mockTicketIssuer.issue(any(IssuanceRequestDTO.class)))
            .thenReturn(issuanceResult);

    RuntimeException failure = assertThrows(RuntimeException.class, () ->
            checkoutService.checkoutMember(
                    VALID_TOKEN,
                    IDEMPOTENCY_KEY,
                    CURRENCY,
                    PAYMENT_METHOD_TOKEN
            )
    );

    assertIssuanceRefusedAndChargeRefunded(failure, 450.0);

    assertEquals(0, activeOrder.countTickets(EVENT_ID_1, ZONE_ID_1, NOW));
    assertEquals(0, activeOrder.countTickets(EVENT_ID_1, ZONE_ID_2, NOW));
    assertEquals(0, activeOrder.countTickets(EVENT_ID_2, ZONE_ID_3, NOW));

    assertEquals(5, zone1.getAvailableAmount());
    assertEquals(5, zone2.getAvailableAmount());
    assertEquals(5, zone3.getAvailableAmount());

    assertEquals(0, zone1.getReservedAmount());
    assertEquals(0, zone2.getReservedAmount());
    assertEquals(0, zone3.getReservedAmount());
}

    // The count check holds both ways: more barcodes than tickets is refused too, not trimmed to fit.
    @Test
    void GivenOneTicketButIssuerReturnsTwoBarcodes_WhenCheckout_ThenTheChargeIsRefundedAndTheTicketReturnedToStock() {
        StandingZone zone = arrangeMemberStandingCart();
        when(mockPaymentGateway.charge(any())).thenReturn(
                new PaymentResultDTO(PAYMENT_TRANSACTION_ID, "gateway", 100.0, CURRENCY, NOW));
        when(mockTicketIssuer.issue(any())).thenReturn(new IssuanceResultDTO(ISSUANCE_TRANSACTION_ID, "issuer", NOW,
                List.of(new BarcodeDTO("barcode-1", "QR"), new BarcodeDTO("barcode-2", "QR"))));

        RuntimeException failure = assertThrows(RuntimeException.class, () ->
                checkoutService.checkoutMember(VALID_TOKEN, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN));

        assertIssuanceRefusedAndChargeRefunded(failure, 100.0);
        assertTrue(mockActiveOrderRepo.getByUserId(USER_ID).isEmpty(), "the rollback must clear the cart");
        assertEquals(5, zone.getAvailableAmount(), "the held place must be back in stock");
    }

    // The issuer answered and checkout refused the answer: the charge is refunded and nothing is saved.
    private void assertIssuanceRefusedAndChargeRefunded(RuntimeException failure, double charged) {
        assertInstanceOf(TicketIssuanceFailedException.class, failure.getCause(),
                "the checkout must fail on the issuer's answer, but the cause was " + failure.getCause());
        verify(mockTicketIssuer).issue(any());
        verify(mockPaymentGateway).refund(PAYMENT_TRANSACTION_ID, charged);
        verify(mockTicketRepo, never()).save(any());
        verify(mockOrderReceiptRepo, never()).save(any());
    }

    // A refund that fails after a charge is the one state that needs an operator: the buyer paid and holds
    // nothing. It must not replace the checkout's own failure, and its ERROR line must name the transaction.
    @Test
    void GivenRefundFailsAfterIssuanceFailure_WhenCheckout_ThenTheIssuanceFailureIsTheCauseAndAnErrorNamesTheTransaction() {
        StandingZone zone = arrangeMemberStandingCart();
        when(mockPaymentGateway.charge(any(PaymentRequestDTO.class))).thenReturn(
                new PaymentResultDTO(PAYMENT_TRANSACTION_ID, "gateway", 100.0, CURRENCY, NOW));
        when(mockTicketIssuer.issue(any(IssuanceRequestDTO.class)))
                .thenThrow(new TicketIssuanceFailedException("rejected by the issuer"));
        doThrow(new RefundFailedException(PAYMENT_TRANSACTION_ID, "declined by the gateway"))
                .when(mockPaymentGateway).refund(PAYMENT_TRANSACTION_ID, 100.0);

        try (LogCapture logs = LogCapture.of(CheckoutService.class)) {
            RuntimeException failure = assertThrows(RuntimeException.class, () ->
                    checkoutService.checkoutMember(VALID_TOKEN, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN));

            assertInstanceOf(TicketIssuanceFailedException.class, failure.getCause(),
                    "a failed refund must not replace the checkout's own failure, but the cause was "
                            + failure.getCause());
            verify(mockPaymentGateway).refund(PAYMENT_TRANSACTION_ID, 100.0);
            List<String> errors = logs.messagesAt(Level.ERROR);
            assertTrue(errors.stream().anyMatch(message -> message.contains("Refund failed")
                            && message.contains("transactionId=" + PAYMENT_TRANSACTION_ID)),
                    "the failed refund must be logged at ERROR naming the transaction, but ERROR carried " + errors);
            assertTrue(logs.throwableClassNamesAt(Level.ERROR).contains(RefundFailedException.class.getName()),
                    "the ERROR line must carry the refund's own failure");
        }
        assertTrue(mockActiveOrderRepo.getByUserId(USER_ID).isEmpty(), "the rollback must clear the cart");
        assertEquals(5, zone.getAvailableAmount());
        assertEquals(0, zone.getReservedAmount());
    }
    @Test
void GivenTicketFromDifferentEventDoesNotExist_WhenCheckout_ThenClearCartAndReturnTicketsToStock() {
    ActiveOrder activeOrder = new ActiveOrder(USER_ID, NOW);
    String orderKey = activeOrder.getOrderKey();
    InventoryZone zone1 = new StandingZone(ZONE_ID_1, "VIP", 5, 100.0);
    InventoryZone zone3 = new StandingZone(ZONE_ID_3, "BALCONY", 5, 200.0);

    zone1.reserve(InventorySelection.standing(1, orderKey));
    zone3.reserve(InventorySelection.standing(1, orderKey));

    activeOrder.addStandingReservation(EVENT_ID_1, ZONE_ID_1, 1, 100.0, NOW);
    activeOrder.addStandingReservation(EVENT_ID_2, ZONE_ID_3, 1, 200.0, NOW);

    when(mockActiveOrderRepo.getByUserId(USER_ID)).thenReturn(activeOrder);

    when(mockEventRepo.findById(EVENT_ID_1)).thenReturn(createRealEventWithZone(EVENT_ID_1, zone1));
    when(mockEventRepo.findById(EVENT_ID_2)).thenThrow(new EventNotFoundException(EVENT_ID_2));

    assertThrows(RuntimeException.class, () ->
            checkoutService.checkoutMember(
                    VALID_TOKEN,
                    IDEMPOTENCY_KEY,
                    CURRENCY,
                    PAYMENT_METHOD_TOKEN
            )
    );

    assertEquals(0, activeOrder.countTickets(EVENT_ID_1, ZONE_ID_1, NOW));
    assertEquals(0, activeOrder.countTickets(EVENT_ID_2, ZONE_ID_3, NOW));

    assertEquals(5, zone1.getAvailableAmount());
    assertEquals(0, zone1.getReservedAmount());
}
   @Test
void GivenPhase3LosesOneVersionRace_WhenCheckout_ThenPhase3RunsAgainAndThePurchaseSucceedsWithOneCharge() {
    arrangeOneStandingLineCheckout();
    doThrow(new ObjectOptimisticLockingFailureException(StandingZone.class, ZONE_ID_1))
            .doNothing()
            .when(mockOrderReceiptRepo).save(any());

    CheckoutResultDTO result = checkoutService.checkoutMember(VALID_TOKEN, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN);

    assertEquals(PAYMENT_TRANSACTION_ID, result.paymentTransactionId());
    verify(mockOrderReceiptRepo, times(2)).save(any());
    verify(mockPaymentGateway, times(1)).charge(any(PaymentRequestDTO.class));
    verify(mockPaymentGateway, never()).refund(anyInt(), anyDouble());
    assertEquals(1, checkoutTransactions.rollbacks(), "the attempt that lost the race must roll back whole");
    assertEquals(1, checkoutTransactions.commits(), "only the attempt that won commits");
}

// On PostgreSQL a sale can also be the victim of a deadlock, or wait out a lock: Phase 3 is just as safe to
// run again after a pessimistic failure as after a lost version race.
@Test
void GivenPhase3IsADeadlockVictimOnce_WhenCheckout_ThenPhase3RunsAgainAndThePurchaseSucceedsWithOneCharge() {
    arrangeOneStandingLineCheckout();
    doThrow(new PessimisticLockingFailureException("deadlock detected"))
            .doNothing()
            .when(mockOrderReceiptRepo).save(any());

    CheckoutResultDTO result = checkoutService.checkoutMember(VALID_TOKEN, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN);

    assertEquals(PAYMENT_TRANSACTION_ID, result.paymentTransactionId());
    verify(mockOrderReceiptRepo, times(2)).save(any());
    verify(mockPaymentGateway, never()).refund(anyInt(), anyDouble());
}

// The retry is bounded: a race lost on every attempt still fails the checkout, which refunds the charge.
@Test
void GivenPhase3LosesEveryVersionRace_WhenCheckout_ThenItFailsAfterThreeAttemptsAndRefundsTheCharge() {
    arrangeOneStandingLineCheckout();
    doThrow(new ObjectOptimisticLockingFailureException(StandingZone.class, ZONE_ID_1))
            .when(mockOrderReceiptRepo).save(any());

    RuntimeException failed = assertThrows(RuntimeException.class,
            () -> checkoutService.checkoutMember(VALID_TOKEN, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN));

    // The race reaches the buyer re-typed as ConcurrentReservationException, which keeps it as its cause.
    assertInstanceOf(ObjectOptimisticLockingFailureException.class, failed.getCause().getCause(),
            "the checkout fails on the race it kept losing, not on something else");
    verify(mockOrderReceiptRepo, times(3)).save(any());
    verify(mockPaymentGateway, times(1)).refund(PAYMENT_TRANSACTION_ID, 100.0);
    assertEquals(3, checkoutTransactions.rollbacks(), "each of the three attempts must roll back whole");
    assertEquals(0, checkoutTransactions.commits(), "no attempt may commit");
}

// The guest path runs its Phase 3 through the same bounded retry.
@Test
void GivenGuestPhase3LosesOneVersionRace_WhenGuestCheckout_ThenPhase3RunsAgainAndThePurchaseSucceedsWithOneCharge() {
    givenValidGuestSession();
    StandingZone zone = new StandingZone(ZONE_ID_1, "VIP", 10, 100.0);
    Event event = createRealEventWithPolicyAndZone(EVENT_ID_1, zone, new NoPurchasePolicy());
    ActiveOrder activeOrder = ActiveOrder.forGuest(VALID_GUEST_SID, NOW);
    activeOrder.addStandingReservation(EVENT_ID_1, ZONE_ID_1, 1, 100.0, NOW);
    zone.reserve(InventorySelection.standing(1, activeOrder.getOrderKey()));
    givenGuestOrder(activeOrder);
    when(mockEventRepo.findById(EVENT_ID_1)).thenReturn(event);
    givenSuccessfulPaymentAndIssuanceWithFlagGuest(1);
    doThrow(new ObjectOptimisticLockingFailureException(StandingZone.class, ZONE_ID_1))
            .doNothing()
            .when(mockOrderReceiptRepo).save(any());

    CheckoutResultDTO result = checkoutService.checkoutGuest(
            VALID_GUEST_SID, GUEST_EMAIL, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN, BUYER_AGE);

    assertEquals(100.0, result.totalCharged());
    verify(mockOrderReceiptRepo, times(2)).save(any());
    verify(mockPaymentGateway, times(1)).charge(any(PaymentRequestDTO.class));
    verify(mockPaymentGateway, never()).refund(anyInt(), anyDouble());
}

// One standing ticket in zone 1 of event 1, charged 100 and issued one barcode.
private void arrangeOneStandingLineCheckout() {
    arrangeMemberStandingCart();
    when(mockPaymentGateway.charge(any(PaymentRequestDTO.class))).thenReturn(
            new PaymentResultDTO(PAYMENT_TRANSACTION_ID, "gateway", 100.0, "ILS", NOW));
    when(mockTicketIssuer.issue(any(IssuanceRequestDTO.class))).thenReturn(new IssuanceResultDTO(
            ISSUANCE_TRANSACTION_ID, "issuer", NOW, List.of(new BarcodeDTO("barcode-value-1", "QR"))));
}

@Test
void GivenValidCheckout_WhenCheckout_ThenReturnCheckoutResultAndSaveTicketAndReceipt() {
    PaymentResultDTO paymentResult = new PaymentResultDTO(
            PAYMENT_TRANSACTION_ID,
            "gateway",
            100.0,
            "ILS",
            NOW
    );

    IssuanceResultDTO issuanceResult = new IssuanceResultDTO(
            ISSUANCE_TRANSACTION_ID,
            "issuer",
            NOW,
            List.of(new BarcodeDTO("barcode-value-1", "QR"))
    );

    arrangeMemberStandingCart();

    when(mockPaymentGateway.charge(any(PaymentRequestDTO.class)))
            .thenReturn(paymentResult);

    when(mockTicketIssuer.issue(any(IssuanceRequestDTO.class)))
            .thenReturn(issuanceResult);

    CheckoutResultDTO result = checkoutService.checkoutMember(
            VALID_TOKEN,
            IDEMPOTENCY_KEY,
            CURRENCY,
            PAYMENT_METHOD_TOKEN
    );

    assertEquals(
            new CheckoutResultDTO(
                        100.0,
                        1,
                    PAYMENT_TRANSACTION_ID,
                    List.of(new CheckoutResultDTO.IssuedTicketDTO(TICKET_ID_1, "barcode-value-1"))
            ),
            result
    );

    ArgumentCaptor<Ticket> ticketCaptor = ArgumentCaptor.forClass(Ticket.class);
    verify(mockTicketRepo, times(1)).save(ticketCaptor.capture());

    Ticket savedTicket = ticketCaptor.getValue();
    assertEquals(TICKET_ID_1, savedTicket.getId());
    assertEquals(EVENT_ID_1, savedTicket.getEventId());
    assertEquals(ZONE_ID_1, savedTicket.getZoneId());
   

    ArgumentCaptor<OrderReceipt> receiptCaptor = ArgumentCaptor.forClass(OrderReceipt.class);
    verify(mockOrderReceiptRepo, times(1)).save(receiptCaptor.capture());

    OrderReceipt savedReceipt = receiptCaptor.getValue();
    assertEquals(USER_ID, savedReceipt.getUserid());
    assertEquals(100.0, savedReceipt.getTotalAmount());

}

   @Test
void GivenValidCheckoutWithCalculatedPrice_WhenCheckout_ThenReturnCalculatedTotalAndSaveTicketAndReceipt() {
    PaymentResultDTO paymentResult = new PaymentResultDTO(
            PAYMENT_TRANSACTION_ID,
            "gateway",
            150.0,
            "ILS",
            NOW
    );

    IssuanceResultDTO issuanceResult = new IssuanceResultDTO(
            ISSUANCE_TRANSACTION_ID,
            "issuer",
            NOW,
            List.of(new BarcodeDTO("barcode-value-2", "QR"))
    );

    StandingZone zone2 = new StandingZone(ZONE_ID_2, "General", 100, 150.0);
    ActiveOrder activeOrder = givenMemberCart();
    holdStanding(activeOrder, EVENT_ID_1, zone2, 1);
    when(mockEventRepo.findById(EVENT_ID_1)).thenReturn(createRealEventWithZone(EVENT_ID_1, zone2));

    when(mockPaymentGateway.charge(any(PaymentRequestDTO.class)))
            .thenReturn(paymentResult);

    when(mockTicketIssuer.issue(any(IssuanceRequestDTO.class)))
            .thenReturn(issuanceResult);

    CheckoutResultDTO result = checkoutService.checkoutMember(
            VALID_TOKEN,
            IDEMPOTENCY_KEY,
            CURRENCY,
            PAYMENT_METHOD_TOKEN
    );

    assertEquals(150.0, result.totalCharged());

    ArgumentCaptor<Ticket> ticketCaptor = ArgumentCaptor.forClass(Ticket.class);
    verify(mockTicketRepo, times(1)).save(ticketCaptor.capture());

    Ticket savedTicket = ticketCaptor.getValue();

    assertEquals(TICKET_ID_1, savedTicket.getId());
    assertEquals(EVENT_ID_1, savedTicket.getEventId());
    assertEquals(ZONE_ID_2, savedTicket.getZoneId());


    ArgumentCaptor<OrderReceipt> receiptCaptor = ArgumentCaptor.forClass(OrderReceipt.class);
    verify(mockOrderReceiptRepo, times(1)).save(receiptCaptor.capture());

    OrderReceipt savedReceipt = receiptCaptor.getValue();

    assertEquals(USER_ID, savedReceipt.getUserid());
    assertEquals(150.0, savedReceipt.getTotalAmount());
    
}
   @Test
void GivenMultipleTicketsFromDifferentZonesSameEvent_WhenCheckout_ThenBuyAllTicketsAndSaveTicketsAndReceipt() {
    PaymentResultDTO paymentResult = new PaymentResultDTO(
            PAYMENT_TRANSACTION_ID,
            "gateway",
            250.0,
            "ILS",
            NOW
    );

    IssuanceResultDTO issuanceResult = new IssuanceResultDTO(
            ISSUANCE_TRANSACTION_ID,
            "issuer",
            NOW,
            List.of(
                    new BarcodeDTO("barcode-zone-1", "QR"),
                    new BarcodeDTO("barcode-zone-2", "QR")
            )
    );

    arrangeMemberCartOfTwoZonesOfOneEvent();

    when(mockPaymentGateway.charge(any(PaymentRequestDTO.class)))
            .thenReturn(paymentResult);

    when(mockTicketIssuer.issue(any(IssuanceRequestDTO.class)))
            .thenReturn(issuanceResult);

    CheckoutResultDTO result = checkoutService.checkoutMember(
            VALID_TOKEN,
            IDEMPOTENCY_KEY,
            CURRENCY,
            PAYMENT_METHOD_TOKEN
    );

    assertEquals(
            new CheckoutResultDTO(
                                    250.0,
                                    1,
                    PAYMENT_TRANSACTION_ID,
                    List.of(
                            new CheckoutResultDTO.IssuedTicketDTO(TICKET_ID_1, "barcode-zone-1"),
                            new CheckoutResultDTO.IssuedTicketDTO(TICKET_ID_2, "barcode-zone-2"))
            ),
            result
    );

    ArgumentCaptor<Ticket> ticketCaptor = ArgumentCaptor.forClass(Ticket.class);
    verify(mockTicketRepo, times(2)).save(ticketCaptor.capture());

    List<Ticket> savedTickets = ticketCaptor.getAllValues();

    assertEquals(2, savedTickets.size());

    assertEquals(TICKET_ID_1, savedTickets.get(0).getId());
    assertEquals(EVENT_ID_1, savedTickets.get(0).getEventId());
    assertEquals(ZONE_ID_1, savedTickets.get(0).getZoneId());


    assertEquals(TICKET_ID_2, savedTickets.get(1).getId());
    assertEquals(EVENT_ID_1, savedTickets.get(1).getEventId());
    assertEquals(ZONE_ID_2, savedTickets.get(1).getZoneId());

    // Each ticket is the buying member's, at its own zone's price.
    assertEquals(List.of(USER_ID, USER_ID), savedTickets.stream().map(Ticket::getHolderUserId).toList());
    assertEquals(List.of(100.0, 150.0), savedTickets.stream().map(Ticket::getPrice).toList());

    ArgumentCaptor<OrderReceipt> receiptCaptor = ArgumentCaptor.forClass(OrderReceipt.class);
    verify(mockOrderReceiptRepo, times(1)).save(receiptCaptor.capture());

    OrderReceipt savedReceipt = receiptCaptor.getValue();

    assertEquals(USER_ID, savedReceipt.getUserid());
    assertEquals(250.0, savedReceipt.getTotalAmount());

}
    // #19: ticket identity comes from the repository, never from the issuer's barcodes.
    @Test
    void GivenRepositoryMintsTicketIds_WhenCheckout_ThenTicketsReceiptLinesAndResultCarryThoseIds() {
        when(mockTicketRepo.nextId()).thenReturn(500, 501);
        PaymentResultDTO paymentResult = new PaymentResultDTO(
                PAYMENT_TRANSACTION_ID, "gateway", 250.0, "ILS", NOW);
        IssuanceResultDTO issuanceResult = new IssuanceResultDTO(
                ISSUANCE_TRANSACTION_ID, "issuer", NOW,
                List.of(new BarcodeDTO("barcode-zone-1", "QR"), new BarcodeDTO("barcode-zone-2", "QR")));
        arrangeMemberCartOfTwoZonesOfOneEvent();
        when(mockPaymentGateway.charge(any(PaymentRequestDTO.class))).thenReturn(paymentResult);
        when(mockTicketIssuer.issue(any(IssuanceRequestDTO.class))).thenReturn(issuanceResult);

        CheckoutResultDTO result = checkoutService.checkoutMember(
                VALID_TOKEN, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN);

        assertEquals(List.of(
                new CheckoutResultDTO.IssuedTicketDTO(500, "barcode-zone-1"),
                new CheckoutResultDTO.IssuedTicketDTO(501, "barcode-zone-2")), result.issuedTickets());
        ArgumentCaptor<Ticket> ticketCaptor = ArgumentCaptor.forClass(Ticket.class);
        verify(mockTicketRepo, times(2)).save(ticketCaptor.capture());
        assertEquals(List.of(500, 501), ticketCaptor.getAllValues().stream().map(Ticket::getId).toList());
        assertEquals(List.of("barcode-zone-1", "barcode-zone-2"),
                ticketCaptor.getAllValues().stream().map(Ticket::getBarcode).toList());
        ArgumentCaptor<OrderReceipt> receiptCaptor = ArgumentCaptor.forClass(OrderReceipt.class);
        verify(mockOrderReceiptRepo).save(receiptCaptor.capture());
        assertEquals(List.of(500, 501),
                receiptCaptor.getValue().getReceiptLines().stream().map(ReceiptLine::getTicketId).toList());
    }

    // ADR-008: the receipt is stamped from the injected clock (T0 here), as UTC wall time.
    @Test
    void GivenFixedClock_WhenCheckoutSucceeds_ThenTheReceiptIsPurchasedAtTheClock() {
        PaymentResultDTO paymentResult = new PaymentResultDTO(
                PAYMENT_TRANSACTION_ID, "gateway", 250.0, "ILS", NOW);
        IssuanceResultDTO issuanceResult = new IssuanceResultDTO(
                ISSUANCE_TRANSACTION_ID, "issuer", NOW,
                List.of(new BarcodeDTO("barcode-zone-1", "QR"), new BarcodeDTO("barcode-zone-2", "QR")));
        arrangeMemberCartOfTwoZonesOfOneEvent();
        when(mockPaymentGateway.charge(any(PaymentRequestDTO.class))).thenReturn(paymentResult);
        when(mockTicketIssuer.issue(any(IssuanceRequestDTO.class))).thenReturn(issuanceResult);

        checkoutService.checkoutMember(VALID_TOKEN, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN);

        ArgumentCaptor<OrderReceipt> receiptCaptor = ArgumentCaptor.forClass(OrderReceipt.class);
        verify(mockOrderReceiptRepo).save(receiptCaptor.capture());
        assertEquals(NOW, receiptCaptor.getValue().getPurchaseTime());
    }

    // What the gateway is asked to charge: the cart's total, in the buyer's currency, on the buyer's card, under
    // the buyer's idempotency key, for the member who is buying.
    @Test
    void GivenMemberCart_WhenCheckout_ThenTheGatewayIsChargedTheTotalInTheCurrencyWithTheKeyAndCard() {
        arrangeMemberCartOfTwoZonesOfOneEvent();
        givenSuccessfulPaymentAndIssuanceWithFlag(2);

        checkoutService.checkoutMember(VALID_TOKEN, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN);

        PaymentRequestDTO request = theChargeRequest();
        assertAll(
                () -> assertEquals(250.0, request.amount(), "the total of 100 and 150"),
                () -> assertEquals(CURRENCY, request.currency()),
                () -> assertEquals(IDEMPOTENCY_KEY, request.idempotencyKey()),
                () -> assertEquals(PAYMENT_METHOD_TOKEN, request.card()),
                () -> assertEquals(USER_ID, request.buyerUserId()),
                () -> assertNull(request.buyerEmail(), "a member is charged by user id"));
    }

    @Test
    void GivenGuestCart_WhenCheckoutGuest_ThenTheGatewayIsChargedTheTotalInTheCurrencyWithTheKeyCardAndEmail() {
        arrangeGuestStandingCart();
        givenSuccessfulPaymentAndIssuanceWithFlagGuest(1);

        checkoutService.checkoutGuest(
                VALID_GUEST_SID, GUEST_EMAIL, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN, BUYER_AGE);

        PaymentRequestDTO request = theChargeRequest();
        assertAll(
                () -> assertEquals(100.0, request.amount()),
                () -> assertEquals(CURRENCY, request.currency()),
                () -> assertEquals(IDEMPOTENCY_KEY, request.idempotencyKey()),
                () -> assertEquals(PAYMENT_METHOD_TOKEN, request.card()),
                () -> assertEquals(GUEST_EMAIL, request.buyerEmail()),
                () -> assertNull(request.buyerUserId(), "a guest has no user id"));
    }

    // An event's discount reaches the buyer: the charge, each ticket and each receipt line carry the discounted
    // price, not the price the places were reserved at.
    @Test
    void GivenEventWithADiscount_WhenCheckout_ThenChargeTicketsAndReceiptLinesCarryTheDiscountedPrice() {
        StandingZone zone = new StandingZone(ZONE_ID_1, "VIP", 10, 100.0);
        Event event = createRealEventWithDiscount(EVENT_ID_1, zone, new DiscountPolicy(20));
        ActiveOrder order = givenMemberCart();
        holdStanding(order, EVENT_ID_1, zone, 2);
        when(mockEventRepo.findById(EVENT_ID_1)).thenReturn(event);
        givenSuccessfulPaymentAndIssuanceWithFlag(2);

        CheckoutResultDTO result = checkoutService.checkoutMember(
                VALID_TOKEN, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN);

        double discounted = 80.0;   // 100 less 20%
        assertEquals(2 * discounted, theChargeRequest().amount(), PRICE_TOLERANCE, "two places at 100, 20% off");
        assertEquals(2 * discounted, result.totalCharged(), PRICE_TOLERANCE);
        ArgumentCaptor<Ticket> tickets = ArgumentCaptor.forClass(Ticket.class);
        verify(mockTicketRepo, times(2)).save(tickets.capture());
        tickets.getAllValues().forEach(ticket -> assertEquals(discounted, ticket.getPrice(), PRICE_TOLERANCE));
        ArgumentCaptor<OrderReceipt> receipt = ArgumentCaptor.forClass(OrderReceipt.class);
        verify(mockOrderReceiptRepo).save(receipt.capture());
        assertEquals(2 * discounted, receipt.getValue().getTotalAmount(), PRICE_TOLERANCE);
        assertEquals(2, receipt.getValue().getReceiptLines().size());
        receipt.getValue().getReceiptLines().forEach(
                line -> assertEquals(discounted, line.getPriceAtReservation(), PRICE_TOLERANCE));
    }

    // The one charge request the checkout sent.
    private PaymentRequestDTO theChargeRequest() {
        ArgumentCaptor<PaymentRequestDTO> requests = ArgumentCaptor.forClass(PaymentRequestDTO.class);
        verify(mockPaymentGateway).charge(requests.capture());
        return requests.getValue();
    }

   @Test
   void GivenTicketsFromDifferentEvents_WhenCheckout_ThenBuyAllTicketsAndSaveTicketsAndReceipt() {
           PaymentResultDTO paymentResult = new PaymentResultDTO(
                           PAYMENT_TRANSACTION_ID,
                           "gateway",
                           300.0,
                           "ILS",
                           NOW);

           IssuanceResultDTO issuanceResult = new IssuanceResultDTO(
                           ISSUANCE_TRANSACTION_ID,
                           "issuer",
                           NOW,
                           List.of(
                                           new BarcodeDTO("barcode-event-1", "QR"),
                                           new BarcodeDTO("barcode-event-2", "QR")));

           StandingZone zone1 = new StandingZone(ZONE_ID_1, "General", 100, 100.0);
           StandingZone zone3 = new StandingZone(ZONE_ID_3, "General", 100, 200.0);
           ActiveOrder activeOrder = givenMemberCart();
           holdStanding(activeOrder, EVENT_ID_1, zone1, 1);
           holdStanding(activeOrder, EVENT_ID_2, zone3, 1);
           when(mockEventRepo.findById(EVENT_ID_1)).thenReturn(createRealEventWithZone(EVENT_ID_1, zone1));
           when(mockEventRepo.findById(EVENT_ID_2)).thenReturn(createRealEventWithZone(EVENT_ID_2, zone3));

           when(mockPaymentGateway.charge(any(PaymentRequestDTO.class)))
                           .thenReturn(paymentResult);

           when(mockTicketIssuer.issue(any(IssuanceRequestDTO.class)))
                           .thenReturn(issuanceResult);

           CheckoutResultDTO result = checkoutService.checkoutMember(
                           VALID_TOKEN,
                           IDEMPOTENCY_KEY,
                           CURRENCY,
                           PAYMENT_METHOD_TOKEN);

           assertEquals(
                           new CheckoutResultDTO(
                                           300.0,
                                           1,
                                           PAYMENT_TRANSACTION_ID,
                                           List.of(
                                                   new CheckoutResultDTO.IssuedTicketDTO(TICKET_ID_1, "barcode-event-1"),
                                                   new CheckoutResultDTO.IssuedTicketDTO(TICKET_ID_2, "barcode-event-2"))),
                           result);

           ArgumentCaptor<Ticket> ticketCaptor = ArgumentCaptor.forClass(Ticket.class);
           verify(mockTicketRepo, times(2)).save(ticketCaptor.capture());

           List<Ticket> savedTickets = ticketCaptor.getAllValues();

           assertEquals(2, savedTickets.size());

           assertEquals(TICKET_ID_1, savedTickets.get(0).getId());
           assertEquals(EVENT_ID_1, savedTickets.get(0).getEventId());
           assertEquals(ZONE_ID_1, savedTickets.get(0).getZoneId());

           assertEquals(TICKET_ID_2, savedTickets.get(1).getId());
           assertEquals(EVENT_ID_2, savedTickets.get(1).getEventId());
           assertEquals(ZONE_ID_3, savedTickets.get(1).getZoneId());

           ArgumentCaptor<OrderReceipt> receiptCaptor = ArgumentCaptor.forClass(OrderReceipt.class);
           verify(mockOrderReceiptRepo, times(1)).save(receiptCaptor.capture());

           OrderReceipt savedReceipt = receiptCaptor.getValue();
           assertEquals(USER_ID, savedReceipt.getUserid());
           assertEquals(300.0, savedReceipt.getTotalAmount());

   }











        @Test
        void GivenSeatedCartLine_WhenCheckout_ThenIssuerReceivesSeatNumberAndTicketSavedWithSeatNumber() {
                PaymentResultDTO paymentResult = new PaymentResultDTO(
                                PAYMENT_TRANSACTION_ID,
                                "gateway",
                                120.0,
                                "ILS",
                                NOW);

                IssuanceResultDTO issuanceResult = new IssuanceResultDTO(
                                ISSUANCE_TRANSACTION_ID,
                                "issuer",
                                NOW,
                                List.of(new BarcodeDTO("barcode-A1", "QR")));

                SeatedZone seatedZoneA1 = new SeatedZone(ZONE_ID_1, "Orchestra", 120.0, List.of(new Seat("A1", 0, 0)));
                ActiveOrder activeOrder = givenMemberCart();
                holdSeats(activeOrder, EVENT_ID_1, seatedZoneA1, "A1");
                when(mockEventRepo.findById(EVENT_ID_1)).thenReturn(createRealEventWithZone(EVENT_ID_1, seatedZoneA1));

                when(mockPaymentGateway.charge(any(PaymentRequestDTO.class))).thenReturn(paymentResult);
                when(mockTicketIssuer.issue(any(IssuanceRequestDTO.class))).thenReturn(issuanceResult);

                checkoutService.checkoutMember(VALID_TOKEN, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN);

                ArgumentCaptor<IssuanceRequestDTO> issuanceCaptor = ArgumentCaptor.forClass(IssuanceRequestDTO.class);
                verify(mockTicketIssuer).issue(issuanceCaptor.capture());

                IssuanceRequestDTO issuanceRequest = issuanceCaptor.getValue();

                assertEquals(1, issuanceRequest.items().size());
                assertEquals("A1", issuanceRequest.items().get(0).seatNumber());

                ArgumentCaptor<Ticket> ticketCaptor = ArgumentCaptor.forClass(Ticket.class);
                verify(mockTicketRepo).save(ticketCaptor.capture());

                Ticket savedTicket = ticketCaptor.getValue();

                assertEquals(EVENT_ID_1, savedTicket.getEventId());
                assertEquals(ZONE_ID_1, savedTicket.getZoneId());
                assertEquals("A1", savedTicket.getSeatNumber());
                assertEquals(TICKET_ID_1, savedTicket.getId());
        }

        

        @Test
        void GivenStandingCartLine_WhenCheckout_ThenIssuerReceivesNullSeatNumber() {
                PaymentResultDTO paymentResult = new PaymentResultDTO(
                                PAYMENT_TRANSACTION_ID,
                                "gateway",
                                100.0,
                                "ILS",
                                NOW);

                IssuanceResultDTO issuanceResult = new IssuanceResultDTO(
                                ISSUANCE_TRANSACTION_ID,
                                "issuer",
                                NOW,
                                List.of(new BarcodeDTO("barcode-standing", "QR")));

                arrangeMemberStandingCart();

                when(mockPaymentGateway.charge(any(PaymentRequestDTO.class))).thenReturn(paymentResult);
                when(mockTicketIssuer.issue(any(IssuanceRequestDTO.class))).thenReturn(issuanceResult);

                checkoutService.checkoutMember(VALID_TOKEN, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN);

                ArgumentCaptor<IssuanceRequestDTO> issuanceCaptor = ArgumentCaptor.forClass(IssuanceRequestDTO.class);
                verify(mockTicketIssuer).issue(issuanceCaptor.capture());

                assertNull(issuanceCaptor.getValue().items().get(0).seatNumber());
        }

        
        @Test
        void GivenSuccessfulSeatedCheckout_WhenCheckout_ThenReservedSeatBecomesSold() {
                ActiveOrder activeOrder = new ActiveOrder(USER_ID, NOW);
                SeatedZone seatedZone = new SeatedZone(
                                ZONE_ID_1,
                                "Orchestra",
                                120.0,
                                List.of(new Seat("A1", 0, 0)));

                seatedZone.reserve(InventorySelection.seated(List.of("A1"), activeOrder.getOrderKey()));

                Event realEvent = createRealEventWithZone(EVENT_ID_1, seatedZone);

                activeOrder.addSeatedReservation(
                                EVENT_ID_1,
                                ZONE_ID_1,
                                List.of("A1"),
                                120.0,
                                NOW);

                PaymentResultDTO paymentResult = new PaymentResultDTO(
                                PAYMENT_TRANSACTION_ID,
                                "gateway",
                                120.0,
                                "ILS",
                                NOW);

                IssuanceResultDTO issuanceResult = new IssuanceResultDTO(
                                ISSUANCE_TRANSACTION_ID,
                                "issuer",
                                NOW,
                                List.of(new BarcodeDTO("barcode-A1", "QR")));

                when(mockActiveOrderRepo.getByUserId(USER_ID)).thenReturn(activeOrder);
                when(mockEventRepo.findById(EVENT_ID_1)).thenReturn(realEvent);
                when(mockPaymentGateway.charge(any(PaymentRequestDTO.class))).thenReturn(paymentResult);
                when(mockTicketIssuer.issue(any(IssuanceRequestDTO.class))).thenReturn(issuanceResult);

                checkoutService.checkoutMember(VALID_TOKEN, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN);

                assertEquals(SeatStatus.SOLD, seatedZone.getSeatStatus("A1"));
                assertTrue(activeOrder.isEmpty());
        }

        
        @Test
        void GivenTicketIssuanceFailsForSeatedOrder_WhenCheckout_ThenReservedSeatIsReleased() {
                ActiveOrder activeOrder = new ActiveOrder(USER_ID, NOW);
                SeatedZone seatedZone = new SeatedZone(
                                ZONE_ID_1,
                                "Orchestra",
                                120.0,
                                List.of(new Seat("A1", 0, 0)));

                seatedZone.reserve(InventorySelection.seated(List.of("A1"), activeOrder.getOrderKey()));

                Event realEvent = createRealEventWithZone(EVENT_ID_1, seatedZone);

                activeOrder.addSeatedReservation(
                                EVENT_ID_1,
                                ZONE_ID_1,
                                List.of("A1"),
                                120.0,
                                NOW);

                PaymentResultDTO paymentResult = new PaymentResultDTO(
                                PAYMENT_TRANSACTION_ID,
                                "gateway",
                                120.0,
                                "ILS",
                                NOW);

                when(mockActiveOrderRepo.getByUserId(USER_ID)).thenReturn(activeOrder);
                when(mockEventRepo.findById(EVENT_ID_1)).thenReturn(realEvent);
                when(mockPaymentGateway.charge(any(PaymentRequestDTO.class))).thenReturn(paymentResult);
                when(mockTicketIssuer.issue(any(IssuanceRequestDTO.class)))
                                .thenThrow(new TicketIssuanceFailedException("rejected by the issuer"));

                assertThrows(RuntimeException.class, () -> checkoutService.checkoutMember(VALID_TOKEN, IDEMPOTENCY_KEY,
                                CURRENCY, PAYMENT_METHOD_TOKEN));

                assertEquals(SeatStatus.AVAILABLE, seatedZone.getSeatStatus("A1"));
                assertTrue(activeOrder.isEmpty());
        }
        
        

        @Test
        void GivenMixedStandingAndSeatedOrder_WhenCheckout_ThenAllTicketsSavedWithCorrectSeatNumbers() {
                PaymentResultDTO paymentResult = new PaymentResultDTO(
                        PAYMENT_TRANSACTION_ID,
                        "gateway",
                        170.0,
                        "ILS",
                        NOW
                );

                IssuanceResultDTO issuanceResult = new IssuanceResultDTO(
                        ISSUANCE_TRANSACTION_ID,
                        "issuer",
                        NOW,
                        List.of(
                                new BarcodeDTO("barcode-standing", "QR"),
                                new BarcodeDTO("barcode-B7", "QR")
                        )
                );

                StandingZone standingZone = new StandingZone(ZONE_ID_1, "Standing", 100, 50.0);
                SeatedZone seatedZone = new SeatedZone(ZONE_ID_2, "Seated", 120.0, List.of(new Seat("B7", 0, 0)));
                ActiveOrder activeOrder = givenMemberCart();
                holdStanding(activeOrder, EVENT_ID_1, standingZone, 1);
                holdSeats(activeOrder, EVENT_ID_1, seatedZone, "B7");
                when(mockEventRepo.findById(EVENT_ID_1))
                        .thenReturn(createRealEventWithZones(EVENT_ID_1, standingZone, seatedZone));

                when(mockPaymentGateway.charge(any(PaymentRequestDTO.class))).thenReturn(paymentResult);
                when(mockTicketIssuer.issue(any(IssuanceRequestDTO.class))).thenReturn(issuanceResult);

                checkoutService.checkoutMember(VALID_TOKEN, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN);

                ArgumentCaptor<Ticket> ticketCaptor = ArgumentCaptor.forClass(Ticket.class);
                verify(mockTicketRepo, times(2)).save(ticketCaptor.capture());

                List<Ticket> savedTickets = ticketCaptor.getAllValues();

                assertNull(savedTickets.get(0).getSeatNumber());
                assertEquals("B7", savedTickets.get(1).getSeatNumber());
        }



        // new test
        @Test
        void GivenReceiptSaveFails_WhenCheckout_ThenSeatReturnedToStockAndCartCleared() {
                // Receipt save fails. Because the sale is committed (confirmInventorySale) LAST — after
                // ticket + receipt persistence — the seat is still RESERVED when the save fails, so the
                // rollback returns it to stock (AVAILABLE) and clears the cart, rather than stranding a
                // SOLD seat. (Payment is refunded too; not asserted here.)
                ActiveOrder activeOrder = new ActiveOrder(USER_ID, NOW);
                SeatedZone seatedZone = new SeatedZone(
                                ZONE_ID_1,
                                "Orchestra",
                                120.0,
                                List.of(new Seat("A1", 0, 0)));
                seatedZone.reserve(InventorySelection.seated(List.of("A1"), activeOrder.getOrderKey()));
                Event realEvent = createRealEventWithZone(EVENT_ID_1, seatedZone);

                activeOrder.addSeatedReservation(
                                EVENT_ID_1,
                                ZONE_ID_1,
                                List.of("A1"),
                                120.0,
                                NOW);

                PaymentResultDTO paymentResult = new PaymentResultDTO(
                                PAYMENT_TRANSACTION_ID,
                                "gateway",
                                120.0,
                                                "ILS",
                                NOW);

                IssuanceResultDTO issuanceResult = new IssuanceResultDTO(
                                ISSUANCE_TRANSACTION_ID,
                                "issuer",
                                NOW,
                                List.of(new BarcodeDTO("barcode-A1", "QR")));
                                
                when(mockActiveOrderRepo.getByUserId(USER_ID)).thenReturn(activeOrder);
                when(mockEventRepo.findById(EVENT_ID_1)).thenReturn(realEvent);
                when(mockPaymentGateway.charge(any(PaymentRequestDTO.class))).thenReturn(paymentResult);
                when(mockTicketIssuer.issue(any(IssuanceRequestDTO.class))).thenReturn(issuanceResult);

                doThrow(new RuntimeException("DB error on receipt save"))
                                .when(mockOrderReceiptRepo).save(any(OrderReceipt.class));

                assertThrows(RuntimeException.class, () -> checkoutService.checkoutMember(VALID_TOKEN, IDEMPOTENCY_KEY,
                                CURRENCY, PAYMENT_METHOD_TOKEN));
                                
                assertEquals(SeatStatus.AVAILABLE, seatedZone.getSeatStatus("A1"));
                assertTrue(activeOrder.isEmpty());
                assertEquals(1, checkoutTransactions.rollbacks(),
                                "Phase 3, which saved the tickets before the receipt failed, must roll back");
                assertEquals(0, checkoutTransactions.commits(), "nothing of the failed sale may commit");
        }

        // Fix #3: a Phase-2 failure rollback over a multi-event cart must release every event
        // independently. If one event's release throws, the remaining events must still be released
        // and the cart must still be cleared — the rollback loop no longer aborts on the first throw.
        @Test
        void GivenMultiEventRollbackWhereOneReleaseFails_WhenCheckout_ThenOtherEventsStillReleasedAndCartCleared() {
                ActiveOrder activeOrder = new ActiveOrder(USER_ID, NOW);
                String orderKey = activeOrder.getOrderKey();

                // Event B is a real standing zone holding a live reservation under this order. Event A's zone
                // no longer holds this order's place, so its real release throws.
                StandingZone zoneA = new StandingZone(ZONE_ID_1, "VIP", 5, 100.0);
                StandingZone zoneB = new StandingZone(ZONE_ID_3, "GA", 5, 200.0);
                zoneB.reserve(InventorySelection.standing(1, orderKey));

                activeOrder.addStandingReservation(EVENT_ID_1, ZONE_ID_1, 1, 100.0, NOW);
                activeOrder.addStandingReservation(EVENT_ID_2, ZONE_ID_3, 1, 200.0, NOW);

                when(mockActiveOrderRepo.getByUserId(USER_ID)).thenReturn(activeOrder);
                when(mockEventRepo.findById(EVENT_ID_1)).thenReturn(createRealEventWithZone(EVENT_ID_1, zoneA));
                when(mockEventRepo.findById(EVENT_ID_2)).thenReturn(createRealEventWithZone(EVENT_ID_2, zoneB));

                // Force a Phase-2 failure (before any inventory is confirmed) so the rollback runs.
                when(mockPaymentGateway.charge(any(PaymentRequestDTO.class)))
                        .thenThrow(new PaymentGatewayException("payment declined by gateway"));

                assertThrows(RuntimeException.class, () ->
                        checkoutService.checkoutMember(VALID_TOKEN, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN));

                // Event A throwing did not strand event B...
                assertEquals(0, zoneB.getReservedAmount());
                assertEquals(5, zoneB.getAvailableAmount());
                // ...and the cart was still cleared.
                assertTrue(activeOrder.isEmpty());
        }

        //////////////////////////// tests for policies
@Test
void GivenUserMeetsAgePolicy_WhenCheckout_ThenCheckoutSucceeds() {
    givenUserAge(20);

    ActiveOrder activeOrder = new ActiveOrder(USER_ID, NOW);
    StandingZone zone = new StandingZone(ZONE_ID_1, "VIP", 10, 100.0);
    zone.reserve(InventorySelection.standing(1, activeOrder.getOrderKey()));

    Event realEvent = createRealEventWithPolicyAndZone(
            EVENT_ID_1,
            zone,
            new AgePurchasePolicy(18)
    );

    activeOrder.addStandingReservation(
            EVENT_ID_1,
            ZONE_ID_1,
            1,
            100.0,
            NOW
    );

    when(mockActiveOrderRepo.getByUserId(USER_ID)).thenReturn(activeOrder);
    when(mockEventRepo.findById(EVENT_ID_1)).thenReturn(realEvent);

    AtomicBoolean paymentCharged = givenSuccessfulPaymentAndIssuanceWithFlag(1);

    CheckoutResultDTO result = checkoutService.checkoutMember(
            VALID_TOKEN,
            IDEMPOTENCY_KEY,
            CURRENCY,
            PAYMENT_METHOD_TOKEN
    );

    assertEquals(100.0, result.totalCharged());
    assertEquals(true, paymentCharged.get());
    assertEquals(true, activeOrder.isEmpty());
    assertEquals(1, result.issuedTicketIds().size());
}
@Test
void GivenUserTooYoungForAgePolicy_WhenCheckout_ThenThrowsPaymentNotChargedNoReceiptNoTicketAndTicketReturnedToStock() {
    givenUserAge(17);

    ActiveOrder activeOrder = new ActiveOrder(USER_ID, NOW);
    StandingZone zone = new StandingZone(ZONE_ID_1, "VIP", 10, 100.0);
    zone.reserve(InventorySelection.standing(1, activeOrder.getOrderKey()));

    Event realEvent = createRealEventWithPolicyAndZone(
            EVENT_ID_1,
            zone,
            new AgePurchasePolicy(18)
    );

    activeOrder.addStandingReservation(
            EVENT_ID_1,
            ZONE_ID_1,
            1,
            100.0,
            NOW
    );

    when(mockActiveOrderRepo.getByUserId(USER_ID)).thenReturn(activeOrder);
    when(mockEventRepo.findById(EVENT_ID_1)).thenReturn(realEvent);

    AtomicBoolean paymentCharged = trackPaymentCharge();

    Exception exception = assertThrows(Exception.class, () -> {
        checkoutService.checkoutMember(
                VALID_TOKEN,
                IDEMPOTENCY_KEY,
                CURRENCY,
                PAYMENT_METHOD_TOKEN
        );
    });

    assertEquals("Checkout failed, tickets returned to stock", exception.getMessage());

    assertFalse(paymentCharged.get());

    verify(mockOrderReceiptRepo, never()).save(any());

    verify(mockTicketRepo, never()).save(any());

    assertTrue(activeOrder.isEmpty());

    assertEquals(10, zone.getAvailableAmount());
}

@Test
void GivenQuantityWithinMaxTicketsPolicy_WhenCheckout_ThenCheckoutSucceeds() {
    givenUserAge(20);

    ActiveOrder activeOrder = new ActiveOrder(USER_ID, NOW);
    StandingZone zone = new StandingZone(ZONE_ID_1, "VIP", 10, 100.0);
    zone.reserve(InventorySelection.standing(2, activeOrder.getOrderKey()));

    Event realEvent = createRealEventWithPolicyAndZone(
            EVENT_ID_1,
            zone,
            new MaxTicketsPurchasePolicy(2)
    );

    activeOrder.addStandingReservation(
            EVENT_ID_1,
            ZONE_ID_1,
            2,
            100.0,
            NOW
    );

    when(mockActiveOrderRepo.getByUserId(USER_ID)).thenReturn(activeOrder);
    when(mockEventRepo.findById(EVENT_ID_1)).thenReturn(realEvent);

    AtomicBoolean paymentCharged = givenSuccessfulPaymentAndIssuanceWithFlag(2);

    CheckoutResultDTO result = checkoutService.checkoutMember(
            VALID_TOKEN,
            IDEMPOTENCY_KEY,
            CURRENCY,
            PAYMENT_METHOD_TOKEN
    );

    assertEquals(200.0, result.totalCharged());
    assertEquals(true, paymentCharged.get());
    assertEquals(true, activeOrder.isEmpty());
    assertEquals(2, result.issuedTicketIds().size());
}
@Test
void GivenQuantityExceedsMaxTicketsPolicy_WhenCheckout_ThenThrowExceptionAndPaymentNotCharged() {
    givenUserAge(20);

    ActiveOrder activeOrder = new ActiveOrder(USER_ID, NOW);
    StandingZone zone = new StandingZone(ZONE_ID_1, "VIP", 10, 100.0);
    zone.reserve(InventorySelection.standing(3, activeOrder.getOrderKey()));

    Event realEvent = createRealEventWithPolicyAndZone(
            EVENT_ID_1,
            zone,
            new MaxTicketsPurchasePolicy(2)
    );

    activeOrder.addStandingReservation(
            EVENT_ID_1,
            ZONE_ID_1,
            3,
            100.0,
            NOW
    );

    when(mockActiveOrderRepo.getByUserId(USER_ID)).thenReturn(activeOrder);
    when(mockEventRepo.findById(EVENT_ID_1)).thenReturn(realEvent);

    AtomicBoolean paymentCharged = trackPaymentCharge();

    String result;

    try {
        checkoutService.checkoutMember(
                VALID_TOKEN,
                IDEMPOTENCY_KEY,
                CURRENCY,
                PAYMENT_METHOD_TOKEN
        );

        result = "NO_EXCEPTION";
    } catch (Exception e) {
        result = e.getMessage();
    }

    assertEquals("Checkout failed, tickets returned to stock", result);
    assertEquals(false, paymentCharged.get());
    assertEquals(true, activeOrder.isEmpty());
}

@Test
void GivenQuantityBelowMinTicketsPolicy_WhenCheckout_ThenThrowExceptionAndPaymentNotCharged() {
    givenUserAge(20);

    ActiveOrder activeOrder = new ActiveOrder(USER_ID, NOW);
    StandingZone zone = new StandingZone(ZONE_ID_1, "VIP", 10, 100.0);
    zone.reserve(InventorySelection.standing(1, activeOrder.getOrderKey()));

    Event realEvent = createRealEventWithPolicyAndZone(
            EVENT_ID_1,
            zone,
            new MinTicketsPurchasePolicy(2)
    );

    activeOrder.addStandingReservation(
            EVENT_ID_1,
            ZONE_ID_1,
            1,
            100.0,
            NOW
    );

    when(mockActiveOrderRepo.getByUserId(USER_ID)).thenReturn(activeOrder);
    when(mockEventRepo.findById(EVENT_ID_1)).thenReturn(realEvent);

    AtomicBoolean paymentCharged = trackPaymentCharge();

    String result;

    try {
        checkoutService.checkoutMember(
                VALID_TOKEN,
                IDEMPOTENCY_KEY,
                CURRENCY,
                PAYMENT_METHOD_TOKEN
        );

        result = "NO_EXCEPTION";
    } catch (Exception e) {
        result = e.getMessage();
    }

    assertEquals("Checkout failed, tickets returned to stock", result);
    assertEquals(false, paymentCharged.get());
    assertEquals(true, activeOrder.isEmpty());
}

@Test
void GivenOrPolicyAndOneConditionIsSatisfied_WhenCheckout_ThenCheckoutSucceeds() {
    givenUserAge(16);

    ActiveOrder activeOrder = new ActiveOrder(USER_ID, NOW);
    StandingZone zone = new StandingZone(ZONE_ID_1, "VIP", 10, 100.0);
    zone.reserve(InventorySelection.standing(3, activeOrder.getOrderKey()));

    PurchasePolicy policy = new OrPurchasePolicy(
            new AgePurchasePolicy(18),
            new MinTicketsPurchasePolicy(3)
    );

    Event realEvent = createRealEventWithPolicyAndZone(
            EVENT_ID_1,
            zone,
            policy
    );

    activeOrder.addStandingReservation(
            EVENT_ID_1,
            ZONE_ID_1,
            3,
            100.0,
            NOW
    );

    when(mockActiveOrderRepo.getByUserId(USER_ID)).thenReturn(activeOrder);
    when(mockEventRepo.findById(EVENT_ID_1)).thenReturn(realEvent);

    AtomicBoolean paymentCharged = givenSuccessfulPaymentAndIssuanceWithFlag(3);

    CheckoutResultDTO result = checkoutService.checkoutMember(
            VALID_TOKEN,
            IDEMPOTENCY_KEY,
            CURRENCY,
            PAYMENT_METHOD_TOKEN
    );

    assertEquals(300.0, result.totalCharged());
    assertEquals(true, paymentCharged.get());
    assertEquals(true, activeOrder.isEmpty());
    assertEquals(3, result.issuedTicketIds().size());
}
@Test
void GivenAndPolicyAndOneConditionIsNotSatisfied_WhenCheckout_ThenThrowExceptionAndPaymentNotCharged() {
    givenUserAge(20);

    ActiveOrder activeOrder = new ActiveOrder(USER_ID, NOW);
    StandingZone zone = new StandingZone(ZONE_ID_1, "VIP", 10, 100.0);
    zone.reserve(InventorySelection.standing(3, activeOrder.getOrderKey()));

    PurchasePolicy policy = new AndPurchasePolicy(
            new AgePurchasePolicy(18),
            new MaxTicketsPurchasePolicy(2)
    );

    Event realEvent = createRealEventWithPolicyAndZone(
            EVENT_ID_1,
            zone,
            policy
    );

    activeOrder.addStandingReservation(
            EVENT_ID_1,
            ZONE_ID_1,
            3,
            100.0,
            NOW
    );

    when(mockActiveOrderRepo.getByUserId(USER_ID)).thenReturn(activeOrder);
    when(mockEventRepo.findById(EVENT_ID_1)).thenReturn(realEvent);

    AtomicBoolean paymentCharged = trackPaymentCharge();

    String result;

    try {
        checkoutService.checkoutMember(
                VALID_TOKEN,
                IDEMPOTENCY_KEY,
                CURRENCY,
                PAYMENT_METHOD_TOKEN
        );

        result = "NO_EXCEPTION";
    } catch (Exception e) {
        result = e.getMessage();
    }

    assertEquals("Checkout failed, tickets returned to stock", result);
    assertEquals(false, paymentCharged.get());
    assertEquals(true, activeOrder.isEmpty());
}

@Test
void GivenTicketsFromDifferentEventsAndSecondEventExceedsMaxPolicy_WhenCheckout_ThenFailWithoutPaymentTicketsOrReceiptAndReturnAllTicketsToStock() {
    givenUserAge(20);

    ActiveOrder activeOrder = new ActiveOrder(USER_ID, NOW);
    StandingZone zoneEvent1 = new StandingZone(ZONE_ID_1, "Event1 Zone", 10, 100.0);
    StandingZone zoneEvent2 = new StandingZone(ZONE_ID_3, "Event2 Zone", 10, 200.0);

    zoneEvent1.reserve(InventorySelection.standing(1, activeOrder.getOrderKey()));
    zoneEvent2.reserve(InventorySelection.standing(2, activeOrder.getOrderKey()));

    Event realEvent1 = createRealEventWithPolicyAndZone(
            EVENT_ID_1,
            zoneEvent1,
            new NoPurchasePolicy()
    );

    Event realEvent2 = createRealEventWithPolicyAndZone(
            EVENT_ID_2,
            zoneEvent2,
            new MaxTicketsPurchasePolicy(1)
    );

    activeOrder.addStandingReservation(
            EVENT_ID_1,
            ZONE_ID_1,
            1,
            100.0,
            NOW
    );

    activeOrder.addStandingReservation(
            EVENT_ID_2,
            ZONE_ID_3,
            2,
            200.0,
            NOW
    );

    when(mockActiveOrderRepo.getByUserId(USER_ID)).thenReturn(activeOrder);
    when(mockEventRepo.findById(EVENT_ID_1)).thenReturn(realEvent1);
    when(mockEventRepo.findById(EVENT_ID_2)).thenReturn(realEvent2);

    AtomicBoolean paymentCharged = trackPaymentCharge();
    AtomicBoolean ticketSaved = trackTicketSave();
    AtomicBoolean receiptSaved = trackReceiptSave();

    String result;

    try {
        checkoutService.checkoutMember(
                VALID_TOKEN,
                IDEMPOTENCY_KEY,
                CURRENCY,
                PAYMENT_METHOD_TOKEN
        );

        result = "NO_EXCEPTION";
    } catch (Exception e) {
        result = e.getMessage();
    }

    assertEquals("Checkout failed, tickets returned to stock", result);

    assertEquals(false, paymentCharged.get());
    assertEquals(false, ticketSaved.get());
    assertEquals(false, receiptSaved.get());

    assertEquals(true, activeOrder.isEmpty());

    assertEquals(10, zoneEvent1.getAvailableAmount());
    assertEquals(0, zoneEvent1.getReservedAmount());
    assertEquals(0, zoneEvent1.getSoldAmount());

    assertEquals(10, zoneEvent2.getAvailableAmount());
    assertEquals(0, zoneEvent2.getReservedAmount());
    assertEquals(0, zoneEvent2.getSoldAmount());
}

@Test
void GivenTicketsFromDifferentEventsAndSecondEventAgePolicyFails_WhenCheckout_ThenFailWithoutPaymentTicketsOrReceiptAndReturnAllTicketsToStock() {
    givenUserAge(20);

    ActiveOrder activeOrder = new ActiveOrder(USER_ID, NOW);
    StandingZone zoneEvent1 = new StandingZone(ZONE_ID_1, "Event1 Zone", 10, 100.0);
    StandingZone zoneEvent2 = new StandingZone(ZONE_ID_3, "Event2 Zone", 10, 200.0);

    zoneEvent1.reserve(InventorySelection.standing(1, activeOrder.getOrderKey()));
    zoneEvent2.reserve(InventorySelection.standing(1, activeOrder.getOrderKey()));

    Event realEvent1 = createRealEventWithPolicyAndZone(
            EVENT_ID_1,
            zoneEvent1,
            new AgePurchasePolicy(18)
    );

    Event realEvent2 = createRealEventWithPolicyAndZone(
            EVENT_ID_2,
            zoneEvent2,
            new AgePurchasePolicy(21)
    );

    activeOrder.addStandingReservation(
            EVENT_ID_1,
            ZONE_ID_1,
            1,
            100.0,
            NOW
    );

    activeOrder.addStandingReservation(
            EVENT_ID_2,
            ZONE_ID_3,
            1,
            200.0,
            NOW
    );

    when(mockActiveOrderRepo.getByUserId(USER_ID)).thenReturn(activeOrder);
    when(mockEventRepo.findById(EVENT_ID_1)).thenReturn(realEvent1);
    when(mockEventRepo.findById(EVENT_ID_2)).thenReturn(realEvent2);

    AtomicBoolean paymentCharged = trackPaymentCharge();
    AtomicBoolean ticketSaved = trackTicketSave();
    AtomicBoolean receiptSaved = trackReceiptSave();

    String result;

    try {
        checkoutService.checkoutMember(
                VALID_TOKEN,
                IDEMPOTENCY_KEY,
                CURRENCY,
                PAYMENT_METHOD_TOKEN
        );

        result = "NO_EXCEPTION";
    } catch (Exception e) {
        result = e.getMessage();
    }

    assertEquals("Checkout failed, tickets returned to stock", result);

    assertEquals(false, paymentCharged.get());
    assertEquals(false, ticketSaved.get());
    assertEquals(false, receiptSaved.get());

    assertEquals(true, activeOrder.isEmpty());

    assertEquals(10, zoneEvent1.getAvailableAmount());
    assertEquals(0, zoneEvent1.getReservedAmount());
    assertEquals(0, zoneEvent1.getSoldAmount());

    assertEquals(10, zoneEvent2.getAvailableAmount());
    assertEquals(0, zoneEvent2.getReservedAmount());
    assertEquals(0, zoneEvent2.getSoldAmount());
}


@Test
void GivenTicketsFromDifferentEventsAndSecondEventBelowMinPolicy_WhenCheckout_ThenFailWithoutPaymentTicketsOrReceiptAndReturnAllTicketsToStock() {
    givenUserAge(20);

    ActiveOrder activeOrder = new ActiveOrder(USER_ID, NOW);
    StandingZone zoneEvent1 = new StandingZone(ZONE_ID_1, "Event1 Zone", 10, 100.0);
    StandingZone zoneEvent2 = new StandingZone(ZONE_ID_3, "Event2 Zone", 10, 200.0);

    zoneEvent1.reserve(InventorySelection.standing(1, activeOrder.getOrderKey()));
    zoneEvent2.reserve(InventorySelection.standing(1, activeOrder.getOrderKey()));

    Event realEvent1 = createRealEventWithPolicyAndZone(
            EVENT_ID_1,
            zoneEvent1,
            new NoPurchasePolicy()
    );

    Event realEvent2 = createRealEventWithPolicyAndZone(
            EVENT_ID_2,
            zoneEvent2,
            new MinTicketsPurchasePolicy(2)
    );

    activeOrder.addStandingReservation(
            EVENT_ID_1,
            ZONE_ID_1,
            1,
            100.0,
            NOW
    );

    activeOrder.addStandingReservation(
            EVENT_ID_2,
            ZONE_ID_3,
            1,
            200.0,
            NOW
    );

    when(mockActiveOrderRepo.getByUserId(USER_ID)).thenReturn(activeOrder);
    when(mockEventRepo.findById(EVENT_ID_1)).thenReturn(realEvent1);
    when(mockEventRepo.findById(EVENT_ID_2)).thenReturn(realEvent2);

    AtomicBoolean paymentCharged = trackPaymentCharge();
    AtomicBoolean ticketSaved = trackTicketSave();
    AtomicBoolean receiptSaved = trackReceiptSave();

    String result;

    try {
        checkoutService.checkoutMember(
                VALID_TOKEN,
                IDEMPOTENCY_KEY,
                CURRENCY,
                PAYMENT_METHOD_TOKEN
        );

        result = "NO_EXCEPTION";
    } catch (Exception e) {
        result = e.getMessage();
    }

    assertEquals("Checkout failed, tickets returned to stock", result);

    assertEquals(false, paymentCharged.get());
    assertEquals(false, ticketSaved.get());
    assertEquals(false, receiptSaved.get());

    assertEquals(true, activeOrder.isEmpty());

    assertEquals(10, zoneEvent1.getAvailableAmount());
    assertEquals(0, zoneEvent1.getReservedAmount());
    assertEquals(0, zoneEvent1.getSoldAmount());

    assertEquals(10, zoneEvent2.getAvailableAmount());
    assertEquals(0, zoneEvent2.getReservedAmount());
    assertEquals(0, zoneEvent2.getSoldAmount());
}


















   // test helper functions:

   private PurchasePolicy acceptingPurchasePolicy() {
           return new NoPurchasePolicy();
        }

        private DiscountPolicy noDiscountPolicy() {
        return new DiscountPolicy(0) {
                @Override
                public double calculate(int quantity, Double priceAtOneTicketReservation, LocalDateTime now) {
                return quantity * priceAtOneTicketReservation;
                }

                @Override
                public double calculatePriceforoneticket(int quantity, Double priceAtOneTicketReservation, LocalDateTime now) {
                return priceAtOneTicketReservation;
                }
        };
        }

        private Event createRealEventWithZone(int eventId, InventoryZone zone) {
        return createRealEventWithZones(eventId, zone);
        }

        private Event createRealEventWithDiscount(int eventId, InventoryZone zone, DiscountPolicy discount) {
        return new Event(
                eventId,
                "Concert",
                4.5,
                List.of("Artist"),
                EventCategory.CONCERT,
                100,
                EventStatus.ON_SALE,
                new VenueMap(1, new Location("Israel", "Tel Aviv"), List.of(zone)),
                List.of(new ShowDate(NOW.plusDays(10), NOW.plusDays(10).plusHours(2))),
                ZoneId.of("Asia/Jerusalem"),
                acceptingPurchasePolicy(),
                discount
        );
        }

        private Event createRealEventWithZones(int eventId, InventoryZone... zones) {
        return new Event(
                eventId,
                "Concert",
                4.5,
                List.of("Artist"),
                EventCategory.CONCERT,
                100,
                EventStatus.ON_SALE,
                new VenueMap(1, new Location("Israel", "Tel Aviv"), List.of(zones)),
                List.of(new ShowDate(NOW.plusDays(10), NOW.plusDays(10).plusHours(2))),
                ZoneId.of("Asia/Jerusalem"),
                acceptingPurchasePolicy(),
                noDiscountPolicy()
        );
        }

    // The member's empty real cart, as the order repository returns it.
    private ActiveOrder givenMemberCart() {
        ActiveOrder order = new ActiveOrder(USER_ID, NOW);
        when(mockActiveOrderRepo.getByUserId(USER_ID)).thenReturn(order);
        return order;
    }

    // Puts quantity standing places of the zone in the cart, held in the zone under the cart's order key.
    private static void holdStanding(ActiveOrder order, int eventId, StandingZone zone, int quantity) {
        order.addStandingReservation(eventId, zone.getId(), quantity, zone.getprice(), NOW);
        zone.reserve(InventorySelection.standing(quantity, order.getOrderKey()));
    }

    // Puts the seats in the cart, held in the zone under the cart's order key.
    private static void holdSeats(ActiveOrder order, int eventId, SeatedZone zone, String... seats) {
        order.addSeatedReservation(eventId, zone.getId(), List.of(seats), zone.getprice(), NOW);
        zone.reserve(InventorySelection.seated(List.of(seats), order.getOrderKey()));
    }

    // One standing place in each of two zones of one real event: zone 1 at 100, zone 2 at 150.
    private void arrangeMemberCartOfTwoZonesOfOneEvent() {
        StandingZone zone1 = new StandingZone(ZONE_ID_1, "General", 100, 100.0);
        StandingZone zone2 = new StandingZone(ZONE_ID_2, "Balcony", 100, 150.0);
        Event event = createRealEventWithZones(EVENT_ID_1, zone1, zone2);
        ActiveOrder order = givenMemberCart();
        holdStanding(order, EVENT_ID_1, zone1, 1);
        holdStanding(order, EVENT_ID_1, zone2, 1);
        when(mockEventRepo.findById(EVENT_ID_1)).thenReturn(event);
    }
private void givenUserAge(int age) {
    User mockUser = mock(User.class);
    when(mockUser.getAge()).thenReturn(age);
    when(mockUserRepository.getUserById(USER_ID)).thenReturn(mockUser);
}

private AtomicBoolean givenSuccessfulPaymentAndIssuanceWithFlag(int ticketCount) {
    AtomicBoolean paymentCharged = new AtomicBoolean(false);

    when(mockPaymentGateway.charge(any(PaymentRequestDTO.class))).thenAnswer(invocation -> {
        paymentCharged.set(true);

        PaymentRequestDTO request = invocation.getArgument(0);

        return new PaymentResultDTO(
                PAYMENT_TRANSACTION_ID,
                "gateway",
                request.amount(),
                CURRENCY,
                NOW
        );
    });

    List<BarcodeDTO> barcodes = new java.util.ArrayList<>();

    for (int i = 1; i <= ticketCount; i = i + 1) {
        barcodes.add(new BarcodeDTO("barcode-policy-" + i, "QR"));
    }

    when(mockTicketIssuer.issue(any(IssuanceRequestDTO.class))).thenReturn(
            new IssuanceResultDTO(
                    ISSUANCE_TRANSACTION_ID,
                    "issuer",
                    NOW,
                    barcodes
            )
    );

    return paymentCharged;
}

private AtomicBoolean trackPaymentCharge() {
    AtomicBoolean paymentCharged = new AtomicBoolean(false);

    when(mockPaymentGateway.charge(any(PaymentRequestDTO.class))).thenAnswer(invocation -> {
        paymentCharged.set(true);

        PaymentRequestDTO request = invocation.getArgument(0);

        return new PaymentResultDTO(
                PAYMENT_TRANSACTION_ID,
                "gateway",
                request.amount(),
                CURRENCY,
                NOW
        );
    });

    return paymentCharged;
}

private Event createRealEventWithPolicyAndZone(int eventId, InventoryZone zone, PurchasePolicy purchasePolicy) {
    return new Event(
            eventId,
            "Policy Test Event",
            4.5,
            List.of("Artist"),
            EventCategory.CONCERT,
            100,
            EventStatus.ON_SALE,
            new VenueMap(1, new Location("Israel", "Tel Aviv"), List.of(zone)),
            List.of(new ShowDate(NOW.plusDays(10), NOW.plusDays(10).plusHours(2))),
            ZoneId.of("Asia/Jerusalem"),
            purchasePolicy,
            noDiscountPolicy()
    );
}
private AtomicBoolean trackTicketSave() {
    AtomicBoolean ticketSaved = new AtomicBoolean(false);

    doAnswer(invocation -> {
        ticketSaved.set(true);
        return null;
    }).when(mockTicketRepo).save(any(Ticket.class));

    return ticketSaved;
}

private AtomicBoolean trackReceiptSave() {
    AtomicBoolean receiptSaved = new AtomicBoolean(false);

    doAnswer(invocation -> {
        receiptSaved.set(true);
        return null;
    }).when(mockOrderReceiptRepo).save(any(OrderReceipt.class));

    return receiptSaved;
}


    

    private static final String VALID_GUEST_SID   = "guest-session-abc";
    private static final String INVALID_GUEST_SID = "guest-session-bad";
    private static final String GUEST_EMAIL       = "guest@example.com";
    private static final int    BUYER_AGE         = 25;

    private void givenValidGuestSession() {
        when(mockiSessionManager.validateCredential(VALID_GUEST_SID)).thenReturn(true);
        when(mockiSessionManager.validateGuestSession(VALID_GUEST_SID)).thenReturn(true);
    }

    private void givenInvalidGuestSession() {
        when(mockiSessionManager.validateCredential(INVALID_GUEST_SID)).thenReturn(false);
        when(mockiSessionManager.validateGuestSession(INVALID_GUEST_SID)).thenReturn(false);
    }

    private void givenGuestOrder(ActiveOrder order) {
        when(mockActiveOrderRepo.getBySessionId(VALID_GUEST_SID)).thenReturn(Optional.of(order));
    }

    private void givenNoGuestOrder() {
        when(mockActiveOrderRepo.getBySessionId(VALID_GUEST_SID)).thenReturn(Optional.empty());
    }

    private AtomicBoolean givenSuccessfulPaymentAndIssuanceWithFlagGuest(int ticketCount) {
        AtomicBoolean charged = new AtomicBoolean(false);
        when(mockPaymentGateway.charge(any(PaymentRequestDTO.class))).thenAnswer(invocation -> {
            charged.set(true);
            PaymentRequestDTO req = invocation.getArgument(0);
            return new PaymentResultDTO(PAYMENT_TRANSACTION_ID, "gateway", req.amount(), CURRENCY, NOW);
        });
        List<BarcodeDTO> barcodes = new java.util.ArrayList<>();
        for (int i = 1; i <= ticketCount; i++) {
            barcodes.add(new BarcodeDTO("barcode-guest-" + i, "QR"));
        }
        when(mockTicketIssuer.issue(any(IssuanceRequestDTO.class))).thenReturn(
                new IssuanceResultDTO("issue-guest-tx", "issuer", NOW, barcodes));
        return charged;
    }

    // --- Guest identity validation ---

    @Test
    void GivenNullGuestSessionId_WhenGuestCheckout_ThenRefusedAsAnInvalidTokenAndNothingCharged() {
        RuntimeException failure = assertThrows(RuntimeException.class, () ->
                checkoutService.checkoutGuest(null, GUEST_EMAIL, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN, BUYER_AGE)
        );

        assertRefusedBeforeAnyCharge(failure, InvalidTokenException.class);
        // SessionExpiredException is an InvalidTokenException too: a missing id must be refused as missing,
        // before any session is looked up, not as a session that has expired.
        assertEquals(InvalidTokenException.class, failure.getCause().getClass(),
                "a missing session id must be refused as missing, but the cause was " + failure.getCause());
    }

    @Test
    void GivenBlankGuestSessionId_WhenGuestCheckout_ThenRefusedAsAnInvalidTokenAndNothingCharged() {
        RuntimeException failure = assertThrows(RuntimeException.class, () ->
                checkoutService.checkoutGuest("  ", GUEST_EMAIL, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN, BUYER_AGE)
        );

        assertRefusedBeforeAnyCharge(failure, InvalidTokenException.class);
        // SessionExpiredException is an InvalidTokenException too: a missing id must be refused as missing,
        // before any session is looked up, not as a session that has expired.
        assertEquals(InvalidTokenException.class, failure.getCause().getClass(),
                "a missing session id must be refused as missing, but the cause was " + failure.getCause());
    }

    @Test
    void GivenInvalidGuestSession_WhenGuestCheckout_ThenRefusedAsAnExpiredSessionAndNothingCharged() {
        givenInvalidGuestSession();
        RuntimeException failure = assertThrows(RuntimeException.class, () ->
                checkoutService.checkoutGuest(INVALID_GUEST_SID, GUEST_EMAIL, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN, BUYER_AGE)
        );

        assertRefusedBeforeAnyCharge(failure, SessionExpiredException.class);
    }

    @Test
    void GivenNullGuestEmail_WhenGuestCheckout_ThenRefusedAsAnInvalidTokenAndNothingCharged() {
        givenValidGuestSession();
        RuntimeException failure = assertThrows(RuntimeException.class, () ->
                checkoutService.checkoutGuest(VALID_GUEST_SID, null, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN, BUYER_AGE)
        );

        assertRefusedBeforeAnyCharge(failure, InvalidTokenException.class);
    }

    @Test
    void GivenBlankGuestEmail_WhenGuestCheckout_ThenRefusedAsAnInvalidTokenAndNothingCharged() {
        givenValidGuestSession();
        RuntimeException failure = assertThrows(RuntimeException.class, () ->
                checkoutService.checkoutGuest(VALID_GUEST_SID, "  ", IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN, BUYER_AGE)
        );

        assertRefusedBeforeAnyCharge(failure, InvalidTokenException.class);
    }

  

    @Test
    void GivenNullIdempotencyKey_WhenGuestCheckout_ThenRefusedAsAnIllegalArgumentAndNothingCharged() {
        givenValidGuestSession();
        RuntimeException failure = assertThrows(RuntimeException.class, () ->
                checkoutService.checkoutGuest(VALID_GUEST_SID, GUEST_EMAIL, null, CURRENCY, PAYMENT_METHOD_TOKEN, BUYER_AGE)
        );

        assertRefusedBeforeAnyCharge(failure, IllegalArgumentException.class);
    }

    @Test
    void GivenNullCurrency_WhenGuestCheckout_ThenRefusedAsAnIllegalArgumentAndNothingCharged() {
        givenValidGuestSession();
        RuntimeException failure = assertThrows(RuntimeException.class, () ->
                checkoutService.checkoutGuest(VALID_GUEST_SID, GUEST_EMAIL, IDEMPOTENCY_KEY, null, PAYMENT_METHOD_TOKEN, BUYER_AGE)
        );

        assertRefusedBeforeAnyCharge(failure, IllegalArgumentException.class);
    }

    @Test
    void GivenNullPaymentMethodToken_WhenGuestCheckout_ThenRefusedAsAnIllegalArgumentAndNothingCharged() {
        givenValidGuestSession();
        RuntimeException failure = assertThrows(RuntimeException.class, () ->
                checkoutService.checkoutGuest(VALID_GUEST_SID, GUEST_EMAIL, IDEMPOTENCY_KEY, CURRENCY, null, BUYER_AGE)
        );

        assertRefusedBeforeAnyCharge(failure, IllegalArgumentException.class);
    }

    // --- Order state ---

    @Test
    void GivenNoGuestActiveOrder_WhenGuestCheckout_ThenRefusedAsNotFoundAndNothingCharged() {
        givenValidGuestSession();
        givenNoGuestOrder();
        RuntimeException failure = assertThrows(RuntimeException.class, () ->
                checkoutService.checkoutGuest(VALID_GUEST_SID, GUEST_EMAIL, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN, BUYER_AGE)
        );

        assertRefusedBeforeAnyCharge(failure, EntityNotFoundException.class);
    }

    @Test
    void GivenAGuestCartWhoseHoldHasRunOut_WhenGuestCheckout_ThenRefusedAndTheHoldIsReleased() {
        givenValidGuestSession();

        StandingZone zone = new StandingZone(ZONE_ID_1, "VIP", 5, 100.0);

        ActiveOrder activeOrder = ActiveOrder.forGuest(VALID_GUEST_SID, NOW);
        activeOrder.addStandingReservation(EVENT_ID_1, ZONE_ID_1, 1, 100.0, aHoldStartThatHasRunOut());
        zone.reserve(InventorySelection.standing(1, activeOrder.getOrderKey()));

        when(mockActiveOrderRepo.getBySessionId(VALID_GUEST_SID)).thenReturn(Optional.of(activeOrder));
        when(mockEventRepo.findById(EVENT_ID_1)).thenAnswer(inv ->
                createRealEventWithPolicyAndZone(EVENT_ID_1, zone, new NoPurchasePolicy()));

        RuntimeException failure = assertThrows(RuntimeException.class, () ->
                checkoutService.checkoutGuest(VALID_GUEST_SID, GUEST_EMAIL, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN, BUYER_AGE)
        );

        assertRefusedBeforeAnyCharge(failure, InvalidStateTransitionException.class);
        assertEquals(0, activeOrder.countTickets(EVENT_ID_1, ZONE_ID_1, NOW));
        assertEquals(5, zone.getAvailableAmount());
        assertEquals(0, zone.getReservedAmount());
    }

    

    @Test
    void GivenBuyerAgePassesAgePolicy_WhenGuestCheckout_ThenCheckoutSucceeds() {
        givenValidGuestSession();

        StandingZone zone = new StandingZone(ZONE_ID_1, "VIP", 10, 100.0);

        Event event = createRealEventWithPolicyAndZone(EVENT_ID_1, zone, new AgePurchasePolicy(18));

        ActiveOrder activeOrder = ActiveOrder.forGuest(VALID_GUEST_SID, NOW);
        activeOrder.addStandingReservation(EVENT_ID_1, ZONE_ID_1, 1, 100.0, NOW);
        zone.reserve(InventorySelection.standing(1, activeOrder.getOrderKey()));

        when(mockActiveOrderRepo.getBySessionId(VALID_GUEST_SID)).thenReturn(Optional.of(activeOrder));
        when(mockEventRepo.findById(EVENT_ID_1)).thenReturn(event);
        givenSuccessfulPaymentAndIssuanceWithFlagGuest(1);

        CheckoutResultDTO result = checkoutService.checkoutGuest(
                VALID_GUEST_SID, GUEST_EMAIL, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN, 20);

        assertEquals(100.0, result.totalCharged());
        assertEquals(1, result.issuedTicketIds().size());
        assertTrue(activeOrder.isEmpty());
        // age must be taken from the parameter, not from userRepository
        verify(mockUserRepository, never()).getUserById(anyInt());
    }

    @Test
    void GivenBuyerAgeTooYoungForAgePolicy_WhenGuestCheckout_ThenThrowExceptionAndPaymentNotCharged() {
        givenValidGuestSession();

        StandingZone zone = new StandingZone(ZONE_ID_1, "VIP", 10, 100.0);

        Event event = createRealEventWithPolicyAndZone(EVENT_ID_1, zone, new AgePurchasePolicy(18));

        ActiveOrder activeOrder = ActiveOrder.forGuest(VALID_GUEST_SID, NOW);
        activeOrder.addStandingReservation(EVENT_ID_1, ZONE_ID_1, 1, 100.0, NOW);
        zone.reserve(InventorySelection.standing(1, activeOrder.getOrderKey()));

        when(mockActiveOrderRepo.getBySessionId(VALID_GUEST_SID)).thenReturn(Optional.of(activeOrder));
        when(mockEventRepo.findById(EVENT_ID_1)).thenReturn(event);

        AtomicBoolean charged = new AtomicBoolean(false);
        when(mockPaymentGateway.charge(any())).thenAnswer(inv -> { charged.set(true); return null; });

        assertThrows(RuntimeException.class, () ->
                checkoutService.checkoutGuest(VALID_GUEST_SID, GUEST_EMAIL, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN, 16)
        );

        assertFalse(charged.get());
        assertTrue(activeOrder.isEmpty());
        assertEquals(10, zone.getAvailableAmount());
    }


    @Test
    void GivenPaymentGatewayFails_WhenGuestCheckout_ThenThrowExceptionAndReturnTicketsToStock() {
        givenValidGuestSession();
;
        StandingZone zone = new StandingZone(ZONE_ID_1, "VIP", 5, 100.0);

        Event event = createRealEventWithPolicyAndZone(EVENT_ID_1, zone, new NoPurchasePolicy());

        ActiveOrder activeOrder = ActiveOrder.forGuest(VALID_GUEST_SID, NOW);
        activeOrder.addStandingReservation(EVENT_ID_1, ZONE_ID_1, 1, 100.0, NOW);
        zone.reserve(InventorySelection.standing(1, activeOrder.getOrderKey()));

        when(mockActiveOrderRepo.getBySessionId(VALID_GUEST_SID)).thenReturn(Optional.of(activeOrder));
        when(mockEventRepo.findById(EVENT_ID_1)).thenReturn(event);
        when(mockPaymentGateway.charge(any())).thenThrow(new PaymentGatewayException("payment declined by gateway"));

        assertThrows(RuntimeException.class, () ->
                checkoutService.checkoutGuest(VALID_GUEST_SID, GUEST_EMAIL, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN, BUYER_AGE)
        );

        assertEquals(0, activeOrder.countTickets(EVENT_ID_1, ZONE_ID_1, NOW));
        assertEquals(5, zone.getAvailableAmount());
        assertEquals(0, zone.getReservedAmount());
    }

    // A guest's session id is the guest's credential, so the failure log must not carry it (#185).
    @Test
    void GivenPaymentGatewayFails_WhenGuestCheckout_ThenNoLogEventCarriesTheSessionId() {
        givenValidGuestSession();
        StandingZone zone = new StandingZone(ZONE_ID_1, "VIP", 5, 100.0);
        Event event = createRealEventWithPolicyAndZone(EVENT_ID_1, zone, new NoPurchasePolicy());
        ActiveOrder activeOrder = ActiveOrder.forGuest(VALID_GUEST_SID, NOW);
        activeOrder.addStandingReservation(EVENT_ID_1, ZONE_ID_1, 1, 100.0, NOW);
        zone.reserve(InventorySelection.standing(1, activeOrder.getOrderKey()));
        when(mockActiveOrderRepo.getBySessionId(VALID_GUEST_SID)).thenReturn(Optional.of(activeOrder));
        when(mockEventRepo.findById(EVENT_ID_1)).thenReturn(event);
        when(mockPaymentGateway.charge(any())).thenThrow(new PaymentGatewayException("payment declined by gateway"));

        try (LogCapture logs = LogCapture.of(CheckoutService.class)) {
            assertThrows(RuntimeException.class, () -> checkoutService.checkoutGuest(
                    VALID_GUEST_SID, GUEST_EMAIL, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN, BUYER_AGE));

            logs.assertNeverLogged(VALID_GUEST_SID);
        }
    }

    @Test
    void GivenTicketIssuerFails_WhenGuestCheckout_ThenThrowExceptionAndRefundPaymentAndReturnTicketsToStock() {
        givenValidGuestSession();

        StandingZone zone = new StandingZone(ZONE_ID_1, "VIP", 5, 100.0);

        Event event = createRealEventWithPolicyAndZone(EVENT_ID_1, zone, new NoPurchasePolicy());

        ActiveOrder activeOrder = ActiveOrder.forGuest(VALID_GUEST_SID, NOW);
        activeOrder.addStandingReservation(EVENT_ID_1, ZONE_ID_1, 1, 100.0, NOW);
        zone.reserve(InventorySelection.standing(1, activeOrder.getOrderKey()));

        when(mockActiveOrderRepo.getBySessionId(VALID_GUEST_SID)).thenReturn(Optional.of(activeOrder));
        when(mockEventRepo.findById(EVENT_ID_1)).thenReturn(event);
        when(mockPaymentGateway.charge(any())).thenAnswer(inv -> {
            PaymentRequestDTO req = inv.getArgument(0);
            return new PaymentResultDTO(PAYMENT_TRANSACTION_ID, "gateway", req.amount(), CURRENCY, NOW);
        });
        when(mockTicketIssuer.issue(any())).thenThrow(new TicketIssuanceFailedException("rejected by the issuer"));

        AtomicBoolean refunded = new AtomicBoolean(false);
        when(mockPaymentGateway.refund(anyInt(), anyDouble())).thenAnswer(inv -> {
            refunded.set(true);
            return null;
        });

        assertThrows(RuntimeException.class, () ->
                checkoutService.checkoutGuest(VALID_GUEST_SID, GUEST_EMAIL, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN, BUYER_AGE)
        );

        assertTrue(refunded.get());
        assertEquals(0, activeOrder.countTickets(EVENT_ID_1, ZONE_ID_1, NOW));
        assertEquals(5, zone.getAvailableAmount());
    }

    // --- Happy path ---

    @Test
    void GivenValidGuestOrderSingleTicket_WhenGuestCheckout_ThenReturnCheckoutResultAndSaveTicketAndReceipt() {
        givenValidGuestSession();

        StandingZone zone = new StandingZone(ZONE_ID_1, "General", 10, 100.0);

        Event event = createRealEventWithPolicyAndZone(EVENT_ID_1, zone, new NoPurchasePolicy());

        ActiveOrder activeOrder = ActiveOrder.forGuest(VALID_GUEST_SID, NOW);
        activeOrder.addStandingReservation(EVENT_ID_1, ZONE_ID_1, 1, 100.0, NOW);
        zone.reserve(InventorySelection.standing(1, activeOrder.getOrderKey()));

        when(mockActiveOrderRepo.getBySessionId(VALID_GUEST_SID)).thenReturn(Optional.of(activeOrder));
        when(mockEventRepo.findById(EVENT_ID_1)).thenReturn(event);
        givenSuccessfulPaymentAndIssuanceWithFlagGuest(1);

        CheckoutResultDTO result = checkoutService.checkoutGuest(
                VALID_GUEST_SID, GUEST_EMAIL, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN, BUYER_AGE);

        assertEquals(100.0, result.totalCharged());
        assertEquals(1, result.issuedTicketIds().size());
        assertTrue(activeOrder.isEmpty());
        ArgumentCaptor<Ticket> ticket = ArgumentCaptor.forClass(Ticket.class);
        verify(mockTicketRepo, times(1)).save(ticket.capture());
        assertNull(ticket.getValue().getHolderUserId(), "a guest's ticket has no member holder");
        assertEquals(100.0, ticket.getValue().getPrice());
        ArgumentCaptor<OrderReceipt> receipt = ArgumentCaptor.forClass(OrderReceipt.class);
        verify(mockOrderReceiptRepo, times(1)).save(receipt.capture());
        // forGuest takes the email and the session id side by side, both Strings: a swap compiles.
        assertEquals(GUEST_EMAIL, receipt.getValue().getGuestEmail());
        assertEquals(VALID_GUEST_SID, receipt.getValue().getGuestSessionId());
        assertNull(receipt.getValue().getUserid(), "a guest's receipt names no member");
    }

    // --- Idempotency ---

    @Test
    void GivenSameIdempotencyKey_WhenGuestCheckoutTwice_ThenPaymentChargedOnlyOnce() {
        givenValidGuestSession();

        StandingZone zone = new StandingZone(ZONE_ID_1, "General", 10, 100.0);

        Event event = createRealEventWithPolicyAndZone(EVENT_ID_1, zone, new NoPurchasePolicy());

        ActiveOrder activeOrder = ActiveOrder.forGuest(VALID_GUEST_SID, NOW);
        activeOrder.addStandingReservation(EVENT_ID_1, ZONE_ID_1, 1, 100.0, NOW);
        zone.reserve(InventorySelection.standing(1, activeOrder.getOrderKey()));

        when(mockActiveOrderRepo.getBySessionId(VALID_GUEST_SID)).thenReturn(Optional.of(activeOrder));
        when(mockEventRepo.findById(EVENT_ID_1)).thenReturn(event);
        givenSuccessfulPaymentAndIssuanceWithFlagGuest(1);

        CheckoutResultDTO first  = checkoutService.checkoutGuest(
                VALID_GUEST_SID, GUEST_EMAIL, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN, BUYER_AGE);
        CheckoutResultDTO second = checkoutService.checkoutGuest(
                VALID_GUEST_SID, GUEST_EMAIL, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN, BUYER_AGE);

        assertEquals(first.totalCharged(), second.totalCharged());
        assertEquals(first.issuedTicketIds(), second.issuedTicketIds());
        verify(mockPaymentGateway, times(1)).charge(any());
    }

    // --- C3: partial multi-event confirm is compensated (no SOLD stranded) ---

    // Confirming SOLD across multiple events is not a single atomic step. If the second confirm throws
    // after the first committed, the first must be returned to stock (SOLD -> AVAILABLE), not left SOLD,
    // and the buyer must be refunded. End state: no inventory SOLD anywhere, both zones available again.
    @Test
    void GivenConfirmFailsMidwayAcrossEvents_WhenCheckout_ThenConfirmedSaleCompensatedAndBuyerRefunded() {
        ActiveOrder activeOrder = new ActiveOrder(USER_ID, NOW);
        String orderKey = activeOrder.getOrderKey();

        StandingZone zone1 = new StandingZone(ZONE_ID_1, "VIP", 5, 100.0);
        StandingZone zone3 = new StandingZone(ZONE_ID_3, "BALCONY", 5, 200.0);
        zone1.reserve(InventorySelection.standing(1, orderKey));
        zone3.reserve(InventorySelection.standing(1, orderKey));

        activeOrder.addStandingReservation(EVENT_ID_1, ZONE_ID_1, 1, 100.0, NOW);
        activeOrder.addStandingReservation(EVENT_ID_2, ZONE_ID_3, 1, 200.0, NOW);

        Event event1 = createRealEventWithZone(EVENT_ID_1, zone1);
        Event event2 = createRealEventWithZone(EVENT_ID_2, zone3);
        when(mockActiveOrderRepo.getByUserId(USER_ID)).thenReturn(activeOrder);
        when(mockEventRepo.findById(EVENT_ID_1)).thenReturn(event1);
        when(mockEventRepo.findById(EVENT_ID_2)).thenReturn(event2);

        PaymentResultDTO paymentResult = new PaymentResultDTO(PAYMENT_TRANSACTION_ID, "gateway", 300.0, "ILS",
                NOW);
        when(mockPaymentGateway.charge(any(PaymentRequestDTO.class))).thenReturn(paymentResult);
        when(mockTicketIssuer.issue(any(IssuanceRequestDTO.class))).thenReturn(new IssuanceResultDTO(
                ISSUANCE_TRANSACTION_ID, "issuer", NOW,
                List.of(new BarcodeDTO("b1", "QR"), new BarcodeDTO("b2", "QR"))));

        // Event 1's confirm commits SOLD; saving event 2's confirm fails once, after event 1 was saved.
        doThrow(new RuntimeException("confirm boom")).doReturn(true).when(mockEventRepo).save(event2);

        assertThrows(RuntimeException.class, () ->
                checkoutService.checkoutMember(VALID_TOKEN, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN));

        // No inventory left SOLD: the confirmed zone was compensated, the other was released.
        assertEquals(0, zone1.getSoldAmount());
        assertEquals(0, zone3.getSoldAmount());
        assertEquals(5, zone1.getAvailableAmount());
        assertEquals(5, zone3.getAvailableAmount());
        // Buyer is refunded (the sale never committed).
        verify(mockPaymentGateway).refund(PAYMENT_TRANSACTION_ID, 300.0);
    }

    // --- C1: idempotent retry survives the market closing ---

    // A completed purchase must be returned idempotently even if the market has since closed — the cache
    // lookup runs before the market gate, so the retry returns the cached receipt instead of throwing.
    @Test
    void GivenCompletedCheckout_WhenMarketClosesThenSameKeyRetry_ThenReturnsCachedResultNotMarketClosed() {
        PaymentResultDTO paymentResult = new PaymentResultDTO(PAYMENT_TRANSACTION_ID, "gateway", 100.0, "ILS",
                NOW);
        IssuanceResultDTO issuanceResult = new IssuanceResultDTO(ISSUANCE_TRANSACTION_ID, "issuer",
                NOW, List.of(new BarcodeDTO("barcode-value-1", "QR")));

        arrangeMemberStandingCart();
        when(mockPaymentGateway.charge(any(PaymentRequestDTO.class))).thenReturn(paymentResult);
        when(mockTicketIssuer.issue(any(IssuanceRequestDTO.class))).thenReturn(issuanceResult);

        CheckoutResultDTO first = checkoutService.checkoutMember(VALID_TOKEN, IDEMPOTENCY_KEY, CURRENCY,
                PAYMENT_METHOD_TOKEN);

        // The market closes after the purchase completed.
        when(mockMarketGate.isOpen()).thenReturn(false);

        // The idempotent retry must still return the cached receipt, not throw MarketNotOpenException.
        CheckoutResultDTO retry = checkoutService.checkoutMember(VALID_TOKEN, IDEMPOTENCY_KEY, CURRENCY,
                PAYMENT_METHOD_TOKEN);

        assertEquals(first, retry);
        verify(mockPaymentGateway, times(1)).charge(any()); // no second charge
    }

    // ---- Defect 2: a cart that cannot be checked out is refused as a state transition (#187) ----
    // Checkout wraps every pre-flight failure in its generic checkout failure, so the reason is in the
    // cause chain. CheckoutPresenter shows "Order expired" only for an InvalidStateTransitionException
    // there; any other cause that is no domain exception reaches the buyer as "Something went wrong".

    @Test
    void GivenMemberCartWhoseHoldHasRunOut_WhenCheckoutMember_ThenRefusedAsAnInvalidStateTransitionAndNothingCharged() {
        ActiveOrder expired = new ActiveOrder(USER_ID, NOW);
        expired.addStandingReservation(EVENT_ID_1, ZONE_ID_1, 1, 100.0, aHoldStartThatHasRunOut());
        when(mockActiveOrderRepo.getByUserId(USER_ID)).thenReturn(expired);

        RuntimeException failed = assertThrows(RuntimeException.class, () ->
                checkoutService.checkoutMember(VALID_TOKEN, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN));

        assertCauseChainHasInvalidStateTransition(failed);
        verify(mockPaymentGateway, never()).charge(any());
    }

    @Test
    void GivenEmptyMemberCart_WhenCheckoutMember_ThenRefusedAsAnInvalidStateTransitionAndNothingCharged() {
        when(mockActiveOrderRepo.getByUserId(USER_ID)).thenReturn(new ActiveOrder(USER_ID, NOW));

        RuntimeException failed = assertThrows(RuntimeException.class, () ->
                checkoutService.checkoutMember(VALID_TOKEN, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN));

        assertCauseChainHasInvalidStateTransition(failed);
        verify(mockPaymentGateway, never()).charge(any());
    }

    @Test
    void GivenGuestCartWhoseHoldHasRunOut_WhenCheckoutGuest_ThenRefusedAsAnInvalidStateTransitionAndNothingCharged() {
        givenValidGuestSession();
        ActiveOrder expired = ActiveOrder.forGuest(VALID_GUEST_SID, NOW);
        expired.addStandingReservation(EVENT_ID_1, ZONE_ID_1, 1, 100.0, aHoldStartThatHasRunOut());
        givenGuestOrder(expired);

        RuntimeException failed = assertThrows(RuntimeException.class, () -> checkoutService.checkoutGuest(
                VALID_GUEST_SID, GUEST_EMAIL, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN, BUYER_AGE));

        assertCauseChainHasInvalidStateTransition(failed);
        verify(mockPaymentGateway, never()).charge(any());
    }

    // A line added this long ago has run out at NOW, by a minute.
    private static LocalDateTime aHoldStartThatHasRunOut() {
        return NOW.minus(CartLineItem.getExpirationLimit()).minusMinutes(1);
    }

    private static void assertCauseChainHasInvalidStateTransition(Throwable failure) {
        List<String> chain = new java.util.ArrayList<>();
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof InvalidStateTransitionException) {
                return;
            }
            chain.add(t.getClass().getSimpleName() + ": " + t.getMessage());
        }
        fail("the refusal must carry an InvalidStateTransitionException, but the cause chain was " + chain);
    }

    // ---- Defect 3: an expected checkout failure is not logged at ERROR (#187) ----
    // ERROR means actionable now (global CLAUDE.md, Observability): a declined card is the buyer's to
    // fix, not an operator's; an unexpected failure still is.

    @Test
    void GivenCardDeclined_WhenCheckoutMember_ThenCheckoutServiceLogsNoError() {
        arrangeMemberStandingCart();
        when(mockPaymentGateway.charge(any())).thenThrow(new PaymentGatewayException("payment declined by gateway"));

        try (LogCapture logs = LogCapture.of(CheckoutService.class)) {
            RuntimeException failed = assertThrows(RuntimeException.class, () ->
                    checkoutService.checkoutMember(VALID_TOKEN, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN));

            assertInstanceOf(PaymentGatewayException.class, failed.getCause(),
                    "arrangement: the checkout fails on the decline");
            assertEquals(List.of(), logs.messagesAt(Level.ERROR),
                    "a declined card needs no operator, so CheckoutService must not log it at ERROR");
        }
    }

    @Test
    void GivenCardDeclined_WhenCheckoutGuest_ThenCheckoutServiceLogsNoError() {
        arrangeGuestStandingCart();
        when(mockPaymentGateway.charge(any())).thenThrow(new PaymentGatewayException("payment declined by gateway"));

        try (LogCapture logs = LogCapture.of(CheckoutService.class)) {
            RuntimeException failed = assertThrows(RuntimeException.class, () -> checkoutService.checkoutGuest(
                    VALID_GUEST_SID, GUEST_EMAIL, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN, BUYER_AGE));

            assertInstanceOf(PaymentGatewayException.class, failed.getCause(),
                    "arrangement: the checkout fails on the decline");
            assertEquals(List.of(), logs.messagesAt(Level.ERROR),
                    "a declined card needs no operator, so CheckoutService must not log it at ERROR");
        }
    }

    @Test
    void GivenUnexpectedFailureFromAPort_WhenCheckoutMember_ThenCheckoutServiceLogsItAtErrorWithTheException() {
        arrangeMemberStandingCart();
        when(mockPaymentGateway.charge(any())).thenThrow(new NullPointerException("gateway bug"));

        try (LogCapture logs = LogCapture.of(CheckoutService.class)) {
            assertThrows(RuntimeException.class, () ->
                    checkoutService.checkoutMember(VALID_TOKEN, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN));

            List<String> errorThrowables = logs.throwableClassNamesAt(Level.ERROR);
            assertTrue(errorThrowables.contains(NullPointerException.class.getName()),
                    "an unexpected failure needs an operator, so it must be logged at ERROR with the exception, "
                            + "but ERROR carried " + errorThrowables);
        }
    }

    @Test
    void GivenCardDeclined_WhenCheckoutMember_ThenTheFailureIsLoggedAtWarnWithTheException() {
        arrangeMemberStandingCart();
        when(mockPaymentGateway.charge(any())).thenThrow(new PaymentGatewayException("payment declined by gateway"));

        try (LogCapture logs = LogCapture.of(CheckoutService.class)) {
            assertThrows(RuntimeException.class, () ->
                    checkoutService.checkoutMember(VALID_TOKEN, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN));

            assertLoggedAtWarnNotError(logs, PaymentGatewayException.class);
        }
    }

    // A second Pay while the first attempt is still paying, as a member paying from a second device sends, is an
    // ordinary refusal: it charges nothing more, leaves the first attempt to finish, and needs no operator
    // (NaimElijah/Ticketeer#240). Phase 1 releases the order lock before the payment, so a second checkout made
    // from inside the charge runs as the second device's would.
    @Test
    void GivenAFirstAttemptStillPaying_WhenASecondCheckoutRuns_ThenItIsRefusedAsCheckoutInProgressWithNoErrorLogged() {
        arrangeMemberStandingCart();
        givenSuccessfulPaymentAndIssuanceWithFlag(1);
        AtomicReference<RuntimeException> secondPay = new AtomicReference<>();
        when(mockPaymentGateway.charge(any(PaymentRequestDTO.class))).thenAnswer(invocation -> {
            try {
                checkoutService.checkoutMember(VALID_TOKEN, "second-tab-key", CURRENCY, PAYMENT_METHOD_TOKEN);
            } catch (RuntimeException refused) {
                secondPay.set(refused);
            }
            PaymentRequestDTO request = invocation.getArgument(0);
            return new PaymentResultDTO(PAYMENT_TRANSACTION_ID, "gateway", request.amount(), CURRENCY, NOW);
        });

        try (LogCapture logs = LogCapture.of(CheckoutService.class)) {
            CheckoutResultDTO first =
                    checkoutService.checkoutMember(VALID_TOKEN, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN);

            assertNotNull(secondPay.get(), "the second checkout must be refused, but it went through");
            assertInstanceOf(CheckoutInProgressException.class, secondPay.get().getCause(),
                    "the second attempt must be refused as already in checkout, but the cause was "
                            + secondPay.get().getCause());
            assertEquals(1, first.issuedTickets().size(), "the first attempt must still complete");
            verify(mockPaymentGateway, times(1)).charge(any());
            assertEquals(List.of(), logs.messagesAt(Level.ERROR), "a second Pay must log no ERROR");
            assertLoggedAtWarnNotError(logs, CheckoutInProgressException.class);
        }
    }

    // Business rule 1: a buyer is charged only the declared amount. A gateway that charged another sum fails the
    // purchase before any ticket, and checkout refunds what the gateway charged, not the sum it asked for.
    @Test
    void GivenGatewayChargesADifferentAmount_WhenCheckout_ThenPurchaseFails() {
        arrangeMemberStandingCart();
        double charged = 120.0;
        when(mockPaymentGateway.charge(any())).thenReturn(
                new PaymentResultDTO(PAYMENT_TRANSACTION_ID, "gateway", charged, CURRENCY, NOW));

        RuntimeException failure = assertThrows(RuntimeException.class, () ->
                checkoutService.checkoutMember(VALID_TOKEN, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN));

        PaymentGatewayException mismatch = assertInstanceOf(PaymentGatewayException.class, failure.getCause(),
                "the purchase must fail on the amount, but the cause was " + failure.getCause());
        assertTrue(mismatch.getMessage().contains("payment amount mismatch"),
                "the failure must name the amount, but was: " + mismatch.getMessage());
        verify(mockTicketIssuer, never()).issue(any());
        verify(mockOrderReceiptRepo, never()).save(any());
        verify(mockPaymentGateway).refund(PAYMENT_TRANSACTION_ID, charged);
    }

    // A gateway answer checkout cannot record, or in another currency than the buyer's, fails the purchase before
    // any ticket is issued, and what the gateway reports it charged is refunded.
    @ParameterizedTest(name = "transaction {0}, gateway \"{1}\", currency {2}, stamped {3}")
    @CsvSource(nullValues = "null", value = {
            "0,   gateway, ILS,  true",
            "201, null,    ILS,  true",
            "201, ' ',     ILS,  true",
            "201, gateway, ILS,  false",
            "201, gateway, null, true",
            "201, gateway, USD,  true"})
    void GivenAPaymentAnswerMissingAFieldOrInAnotherCurrency_WhenCheckout_ThenNoTicketIsIssuedAndTheChargeIsRefunded(
            int transactionId, String gatewayName, String currency, boolean stamped) {
        StandingZone zone = arrangeMemberStandingCart();
        when(mockPaymentGateway.charge(any())).thenReturn(
                new PaymentResultDTO(transactionId, gatewayName, 100.0, currency, stamped ? NOW : null));

        RuntimeException failure = assertThrows(RuntimeException.class, () ->
                checkoutService.checkoutMember(VALID_TOKEN, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN));

        assertInstanceOf(PaymentGatewayException.class, failure.getCause(),
                "the purchase must fail on the gateway's answer, but the cause was " + failure.getCause());
        verify(mockTicketIssuer, never()).issue(any());
        verify(mockTicketRepo, never()).save(any());
        verify(mockOrderReceiptRepo, never()).save(any());
        verify(mockPaymentGateway).refund(transactionId, 100.0);
        assertEquals(5, zone.getAvailableAmount(), "the held place must be back in stock");
    }

    // An issuer answer checkout cannot record is refused like an issuer failure: the charge is refunded.
    @ParameterizedTest(name = "transaction \"{0}\", issuer \"{1}\", stamped {2}, barcode \"{3}\"")
    @CsvSource(nullValues = "null", value = {
            "null,       issuer, true,  barcode-1",
            "' ',        issuer, true,  barcode-1",
            "issue-tx-1, null,   true,  barcode-1",
            "issue-tx-1, ' ',    true,  barcode-1",
            "issue-tx-1, issuer, false, barcode-1",
            "issue-tx-1, issuer, true,  null",
            "issue-tx-1, issuer, true,  ' '"})
    void GivenAnIssuerAnswerWithABlankBarcodeOrAMissingField_WhenCheckout_ThenTheChargeIsRefundedAndNoTicketSaved(
            String issuanceTransactionId, String issuerName, boolean stamped, String barcode) {
        StandingZone zone = arrangeMemberStandingCart();
        when(mockPaymentGateway.charge(any())).thenReturn(
                new PaymentResultDTO(PAYMENT_TRANSACTION_ID, "gateway", 100.0, CURRENCY, NOW));
        when(mockTicketIssuer.issue(any())).thenReturn(new IssuanceResultDTO(
                issuanceTransactionId, issuerName, stamped ? NOW : null, List.of(new BarcodeDTO(barcode, "QR"))));

        RuntimeException failure = assertThrows(RuntimeException.class, () ->
                checkoutService.checkoutMember(VALID_TOKEN, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN));

        assertIssuanceRefusedAndChargeRefunded(failure, 100.0);
        assertEquals(5, zone.getAvailableAmount(), "the held place must be back in stock");
    }

    // Phase 3 re-reads the order. One that left checkout while the buyer paid must not be sold.
    @Test
    void GivenOrderNoLongerInCheckoutAfterThePayment_WhenCheckout_ThenTheChargeIsRefundedAndNoTicketSaved() {
        StandingZone zone = arrangeMemberStandingCart();
        ActiveOrder order = mockActiveOrderRepo.getByUserId(USER_ID);
        when(mockPaymentGateway.charge(any())).thenAnswer(invocation -> {
            order.cancelCheckoutInProgress();
            return new PaymentResultDTO(PAYMENT_TRANSACTION_ID, "gateway", 100.0, CURRENCY, NOW);
        });
        givenIssuerAnswersBarcodes(1);

        RuntimeException failure = assertThrows(RuntimeException.class, () ->
                checkoutService.checkoutMember(VALID_TOKEN, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN));

        assertInstanceOf(InvalidStateTransitionException.class, failure.getCause(),
                "the sale must stop on the order that left checkout, but the cause was " + failure.getCause());
        assertNothingSoldAndTheChargeRefunded(zone);
    }

    // Phase 3 compares the order's lines with the snapshot Phase 1 took. A cart that changed while the buyer paid
    // must not be sold: the charge was for the old lines.
    @Test
    void GivenCartChangedDuringThePayment_WhenCheckout_ThenTheChargeIsRefundedAndNoTicketSaved() {
        StandingZone zone = arrangeMemberStandingCart();
        ActiveOrder order = mockActiveOrderRepo.getByUserId(USER_ID);
        when(mockPaymentGateway.charge(any())).thenAnswer(invocation -> {
            order.cancelCheckoutInProgress();
            holdStanding(order, EVENT_ID_1, zone, 1);
            order.markCheckoutInProgress();
            return new PaymentResultDTO(PAYMENT_TRANSACTION_ID, "gateway", 100.0, CURRENCY, NOW);
        });
        givenIssuerAnswersBarcodes(1);

        RuntimeException failure = assertThrows(RuntimeException.class, () ->
                checkoutService.checkoutMember(VALID_TOKEN, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN));

        assertInstanceOf(ConcurrentReservationException.class, failure.getCause(),
                "the sale must stop on the changed cart, but the cause was " + failure.getCause());
        assertNothingSoldAndTheChargeRefunded(zone);
    }

    private void assertNothingSoldAndTheChargeRefunded(StandingZone zone) {
        verify(mockPaymentGateway).refund(PAYMENT_TRANSACTION_ID, 100.0);
        verify(mockTicketRepo, never()).save(any());
        verify(mockOrderReceiptRepo, never()).save(any());
        assertEquals(0, zone.getSoldAmount(), "nothing may be sold");
        assertEquals(5, zone.getAvailableAmount(), "every held place must be back in stock");
    }

    // C2: once the sale has committed, consuming the cart is best-effort. A failed delete must neither fail nor
    // refund the purchase; it is logged at WARN.
    @Test
    void GivenConsumedOrderDeleteFails_WhenCheckoutSucceeds_ThenThePurchaseStandsAndNothingIsRefunded() {
        StandingZone zone = arrangeMemberStandingCart();
        givenSuccessfulPaymentAndIssuanceWithFlag(1);
        doThrow(new IllegalStateException("order store unavailable")).when(mockActiveOrderRepo).delete(any());

        try (LogCapture logs = LogCapture.of(CheckoutService.class)) {
            CheckoutResultDTO result = checkoutService.checkoutMember(
                    VALID_TOKEN, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN);

            assertEquals(PAYMENT_TRANSACTION_ID, result.paymentTransactionId());
            verify(mockPaymentGateway, never()).refund(anyInt(), anyDouble());
            assertEquals(1, zone.getSoldAmount(), "the sale must stay committed");
            List<String> warnThrowables = logs.throwableClassNamesAt(Level.WARN);
            assertTrue(warnThrowables.contains(IllegalStateException.class.getName()),
                    "the failed cleanup must be logged at WARN with its exception, but WARN carried " + warnThrowables);
        }
    }

    // The member hears of a failed checkout through a notice, after commit (ADR-004).
    @Test
    void GivenCardDeclined_WhenCheckoutMember_ThenAPurchaseFailedNoticeIsPublishedForTheMember() {
        arrangeMemberStandingCart();
        when(mockPaymentGateway.charge(any())).thenThrow(new PaymentGatewayException("payment declined by gateway"));

        assertThrows(RuntimeException.class, () ->
                checkoutService.checkoutMember(VALID_TOKEN, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN));

        ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
        verify(mockEventPublisher).publishEvent(published.capture());
        PurchaseFailedNotice notice = assertInstanceOf(PurchaseFailedNotice.class, published.getValue(),
                "a failed checkout must publish a purchase-failed notice, and nothing else");
        assertEquals(USER_ID, notice.userId());
    }

    // A failed checkout is not remembered under its key, so the buyer can try again with the same key.
    @Test
    void GivenAFailedCheckout_WhenRetriedWithTheSameKey_ThenItIsProcessedAgain() {
        StandingZone zone = arrangeMemberStandingCart();
        ActiveOrder order = mockActiveOrderRepo.getByUserId(USER_ID);
        when(mockPaymentGateway.charge(any()))
                .thenThrow(new PaymentGatewayException("payment declined by gateway"))
                .thenReturn(new PaymentResultDTO(PAYMENT_TRANSACTION_ID, "gateway", 100.0, CURRENCY, NOW));
        givenIssuerAnswersBarcodes(1);
        assertThrows(RuntimeException.class, () ->
                checkoutService.checkoutMember(VALID_TOKEN, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN));
        holdStanding(order, EVENT_ID_1, zone, 1);   // the rollback emptied the cart; the buyer chooses again

        CheckoutResultDTO retry = checkoutService.checkoutMember(
                VALID_TOKEN, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN);

        assertEquals(PAYMENT_TRANSACTION_ID, retry.paymentTransactionId());
        verify(mockPaymentGateway, times(2)).charge(any());
        assertEquals(1, zone.getSoldAmount());
    }

    // A key is one buyer's. Another member who sends it is refused and not charged, and never sees the first
    // buyer's purchase.
    @Test
    void GivenKeyUsedByAnotherBuyer_WhenCheckout_ThenConflictAndNoCharge() {
        StandingZone zone = arrangeMemberStandingCart();
        givenSuccessfulPaymentAndIssuanceWithFlag(1);
        checkoutService.checkoutMember(VALID_TOKEN, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN);
        String otherToken = "other-member-token";
        int otherUserId = USER_ID + 1;
        when(mockiSessionManager.validateToken(otherToken)).thenReturn(true);
        when(mockiSessionManager.extractUserId(otherToken)).thenReturn(otherUserId);
        User otherMember = mock(User.class);
        when(otherMember.getAge()).thenReturn(30);
        when(mockUserRepository.getUserById(otherUserId)).thenReturn(otherMember);
        ActiveOrder otherCart = new ActiveOrder(otherUserId, NOW);
        holdStanding(otherCart, EVENT_ID_1, zone, 1);
        when(mockActiveOrderRepo.getByUserId(otherUserId)).thenReturn(otherCart);

        RuntimeException failure = assertThrows(RuntimeException.class, () ->
                checkoutService.checkoutMember(otherToken, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN));

        assertInstanceOf(IdempotencyConflictException.class, failure.getCause(),
                "a key another buyer used must be refused as a conflict, but the cause was " + failure.getCause());
        verify(mockPaymentGateway, times(1)).charge(any());
    }

    // The issuer answers one barcode per ticket.
    private void givenIssuerAnswersBarcodes(int count) {
        List<BarcodeDTO> barcodes = new java.util.ArrayList<>();
        for (int i = 1; i <= count; i++) {
            barcodes.add(new BarcodeDTO("barcode-" + i, "QR"));
        }
        when(mockTicketIssuer.issue(any())).thenReturn(
                new IssuanceResultDTO(ISSUANCE_TRANSACTION_ID, "issuer", NOW, barcodes));
    }

    // An operator reconciling a failed refund needs the sum the buyer is owed: what the gateway took, which an
    // amount mismatch makes differ from the order's total.
    @Test
    void GivenRefundFailsAfterAnAmountMismatch_WhenCheckout_ThenTheErrorNamesTheChargedAmount() {
        arrangeMemberStandingCart();
        double charged = 120.0;
        when(mockPaymentGateway.charge(any())).thenReturn(
                new PaymentResultDTO(PAYMENT_TRANSACTION_ID, "gateway", charged, CURRENCY, NOW));
        doThrow(new RefundFailedException(PAYMENT_TRANSACTION_ID, "declined by the gateway"))
                .when(mockPaymentGateway).refund(PAYMENT_TRANSACTION_ID, charged);

        try (LogCapture logs = LogCapture.of(CheckoutService.class)) {
            assertThrows(RuntimeException.class, () ->
                    checkoutService.checkoutMember(VALID_TOKEN, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN));

            List<String> errors = logs.messagesAt(Level.ERROR);
            assertTrue(errors.stream().anyMatch(message -> message.contains("Refund failed")
                            && message.contains("transactionId=" + PAYMENT_TRANSACTION_ID)
                            && message.contains("amount=" + charged)),
                    "the failed refund's ERROR line must name the charged amount, but ERROR carried " + errors);
        }
    }

    // A race lost on every Phase 3 attempt is an expected outcome under load, not an operator's problem.
    @Test
    void GivenPhase3LosesEveryVersionRace_WhenCheckoutMember_ThenTheFailureIsLoggedAtWarnWithTheException() {
        arrangeOneStandingLineCheckout();
        doThrow(new ObjectOptimisticLockingFailureException(StandingZone.class, ZONE_ID_1))
                .when(mockOrderReceiptRepo).save(any());

        try (LogCapture logs = LogCapture.of(CheckoutService.class)) {
            assertThrows(RuntimeException.class, () ->
                    checkoutService.checkoutMember(VALID_TOKEN, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN));

            assertLoggedAtWarnNotError(logs, ObjectOptimisticLockingFailureException.class);
        }
    }

    // An attempt whose freeze save loses a version race leaves the order alone (CheckoutFreezeRaceJpaTest);
    // the skipped rollback is a state transition worth one INFO line, and the lost race itself a WARN.
    @Test
    void GivenFreezeSaveLosesAVersionRace_WhenCheckoutMember_ThenTheSkippedRollbackIsLoggedAtInfoNamingTheOrder() {
        arrangeMemberStandingCart();
        String orderKey = mockActiveOrderRepo.getByUserId(USER_ID).getOrderKey();
        doAnswer(invocation -> {
            if (invocation.<ActiveOrder>getArgument(0).isCheckoutInProgress()) {
                throw new ObjectOptimisticLockingFailureException(ActiveOrder.class, orderKey);
            }
            return null;
        }).when(mockActiveOrderRepo).save(any());

        try (LogCapture logs = LogCapture.of(CheckoutService.class)) {
            assertThrows(RuntimeException.class, () ->
                    checkoutService.checkoutMember(VALID_TOKEN, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN));

            List<String> info = logs.messagesAt(Level.INFO);
            assertTrue(info.stream().anyMatch(message -> message.contains(orderKey)),
                    "the skipped rollback must be logged at INFO naming the order, but INFO carried " + info);
            assertLoggedAtWarnNotError(logs, ObjectOptimisticLockingFailureException.class);
            verify(mockPaymentGateway, never()).charge(any());
        }
    }

    private static void assertLoggedAtWarnNotError(LogCapture logs, Class<? extends Throwable> failure) {
        List<String> warnThrowables = logs.throwableClassNamesAt(Level.WARN);
        List<String> errorThrowables = logs.throwableClassNamesAt(Level.ERROR);
        assertAll(
                () -> assertTrue(warnThrowables.contains(failure.getName()),
                        "an expected checkout failure must be logged at WARN with the exception, but WARN carried "
                                + warnThrowables),
                () -> assertFalse(errorThrowables.contains(failure.getName()),
                        "an expected checkout failure needs no operator, but ERROR carried " + errorThrowables));
    }

    // ---- G1: an outage is logged at ERROR, though it is a DomainException (#187) ----
    // An unreachable payment gateway may have charged the card with no receipt and no refund (#99), and an
    // unreachable issuer or external service needs an operator too. A refusal and a lost race stay WARN.

    @Test
    void GivenPaymentGatewayUnreachable_WhenCheckoutMember_ThenTheFailureIsLoggedAtErrorWithTheException() {
        arrangeMemberStandingCart();
        when(mockPaymentGateway.charge(any())).thenThrow(new PaymentGatewayUnreachableException("timed out"));

        assertCheckoutFailureLoggedAtErrorNotWarn(PaymentGatewayUnreachableException.class);
    }

    @Test
    void GivenTicketIssuerUnreachable_WhenCheckoutMember_ThenTheFailureIsLoggedAtErrorWithTheException() {
        arrangeMemberStandingCart();
        givenSuccessfulPaymentAndIssuanceWithFlag(1);
        when(mockTicketIssuer.issue(any())).thenThrow(
                new TicketIssuerUnreachableException("ticket issuer unreachable", new RuntimeException("refused")));

        assertCheckoutFailureLoggedAtErrorNotWarn(TicketIssuerUnreachableException.class);
    }

    @Test
    void GivenExternalServiceUnavailable_WhenCheckoutMember_ThenTheFailureIsLoggedAtErrorWithTheException() {
        arrangeMemberStandingCart();
        when(mockPaymentGateway.charge(any())).thenThrow(new ExternalServiceUnavailableException("no payment service"));

        assertCheckoutFailureLoggedAtErrorNotWarn(ExternalServiceUnavailableException.class);
    }

    // Guard: an issuer that answered with a refusal was reachable, so its failure stays WARN.
    @Test
    void GivenTicketIssuerRefuses_WhenCheckoutMember_ThenTheFailureIsLoggedAtWarnWithTheException() {
        arrangeMemberStandingCart();
        givenSuccessfulPaymentAndIssuanceWithFlag(1);
        when(mockTicketIssuer.issue(any())).thenThrow(new TicketIssuanceFailedException("rejected by the issuer"));

        try (LogCapture logs = LogCapture.of(CheckoutService.class)) {
            assertThrows(RuntimeException.class, () ->
                    checkoutService.checkoutMember(VALID_TOKEN, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN));

            assertLoggedAtWarnNotError(logs, TicketIssuanceFailedException.class);
        }
    }

    private void assertCheckoutFailureLoggedAtErrorNotWarn(Class<? extends Throwable> outage) {
        try (LogCapture logs = LogCapture.of(CheckoutService.class)) {
            RuntimeException failed = assertThrows(RuntimeException.class, () ->
                    checkoutService.checkoutMember(VALID_TOKEN, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN));

            assertInstanceOf(outage, failed.getCause(), "arrangement: the checkout fails on the outage");
            List<String> errorThrowables = logs.throwableClassNamesAt(Level.ERROR);
            List<String> warnThrowables = logs.throwableClassNamesAt(Level.WARN);
            assertAll(
                    () -> assertTrue(errorThrowables.contains(outage.getName()),
                            "an outage needs an operator, so it must be logged at ERROR with the exception, "
                                    + "but ERROR carried " + errorThrowables),
                    () -> assertFalse(warnThrowables.contains(outage.getName()),
                            "an outage must not be logged as an expected failure, but WARN carried "
                                    + warnThrowables));
        }
    }

    // One standing ticket of a real event, held by the member's real cart; returns the zone, of five places.
    private StandingZone arrangeMemberStandingCart() {
        StandingZone zone = new StandingZone(ZONE_ID_1, "VIP", 5, 100.0);
        Event event = createRealEventWithPolicyAndZone(EVENT_ID_1, zone, new NoPurchasePolicy());
        ActiveOrder order = new ActiveOrder(USER_ID, NOW);
        order.addStandingReservation(EVENT_ID_1, ZONE_ID_1, 1, 100.0, NOW);
        zone.reserve(InventorySelection.standing(1, order.getOrderKey()));
        when(mockActiveOrderRepo.getByUserId(USER_ID)).thenReturn(order);
        when(mockEventRepo.findById(EVENT_ID_1)).thenReturn(event);
        return zone;
    }

    // One standing ticket of a real event, held by a live guest's real cart.
    private void arrangeGuestStandingCart() {
        givenValidGuestSession();
        StandingZone zone = new StandingZone(ZONE_ID_1, "VIP", 5, 100.0);
        Event event = createRealEventWithPolicyAndZone(EVENT_ID_1, zone, new NoPurchasePolicy());
        ActiveOrder order = ActiveOrder.forGuest(VALID_GUEST_SID, NOW);
        order.addStandingReservation(EVENT_ID_1, ZONE_ID_1, 1, 100.0, NOW);
        zone.reserve(InventorySelection.standing(1, order.getOrderKey()));
        givenGuestOrder(order);
        when(mockEventRepo.findById(EVENT_ID_1)).thenReturn(event);
    }

    // ---- Defect 9: a failed order unlock in checkout's finally is logged, not swallowed (#187) ----
    // On success the order is unlocked twice: after Phase 1, and in finally. A failed checkout also
    // unlocks it in its rollback, between the two.

    @Test
    void GivenFinalOrderUnlockFails_WhenCheckoutMemberSucceeds_ThenThePurchaseStandsAndTheFailureIsLoggedAtWarn() {
        arrangeMemberStandingCart();
        givenSuccessfulPaymentAndIssuanceWithFlag(1);
        doNothing().doThrow(new IllegalMonitorStateException("order lock not held"))
                .when(mockActiveOrderRepo).unlock(any());

        try (LogCapture logs = LogCapture.of(CheckoutService.class)) {
            CheckoutResultDTO result = checkoutService.checkoutMember(
                    VALID_TOKEN, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN);

            assertEquals(PAYMENT_TRANSACTION_ID, result.paymentTransactionId(),
                    "a failed unlock after the sale committed must not undo the purchase");
            assertUnlockFailureLoggedAtWarn(logs);
            verify(mockActiveOrderRepo, times(2)).unlock(any());
        }
    }

    @Test
    void GivenFinalOrderUnlockFails_WhenCheckoutGuestSucceeds_ThenThePurchaseStandsAndTheFailureIsLoggedAtWarn() {
        arrangeGuestStandingCart();
        givenSuccessfulPaymentAndIssuanceWithFlagGuest(1);
        doNothing().doThrow(new IllegalMonitorStateException("order lock not held"))
                .when(mockActiveOrderRepo).unlock(any());

        try (LogCapture logs = LogCapture.of(CheckoutService.class)) {
            CheckoutResultDTO result = checkoutService.checkoutGuest(
                    VALID_GUEST_SID, GUEST_EMAIL, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN, BUYER_AGE);

            assertEquals(PAYMENT_TRANSACTION_ID, result.paymentTransactionId(),
                    "a failed unlock after the sale committed must not undo the purchase");
            assertUnlockFailureLoggedAtWarn(logs);
            verify(mockActiveOrderRepo, times(2)).unlock(any());
        }
    }

    @Test
    void GivenFinalOrderUnlockFails_WhenCheckoutMemberIsDeclined_ThenTheDeclineIsTheOutcomeAndTheFailureIsLoggedAtWarn() {
        arrangeMemberStandingCart();
        when(mockPaymentGateway.charge(any())).thenThrow(new PaymentGatewayException("payment declined by gateway"));
        doNothing().doNothing().doThrow(new IllegalMonitorStateException("order lock not held"))
                .when(mockActiveOrderRepo).unlock(any());

        try (LogCapture logs = LogCapture.of(CheckoutService.class)) {
            RuntimeException failed = assertThrows(RuntimeException.class, () ->
                    checkoutService.checkoutMember(VALID_TOKEN, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN));

            assertInstanceOf(PaymentGatewayException.class, failed.getCause(),
                    "a failed unlock must not replace the checkout's own failure");
            assertUnlockFailureLoggedAtWarn(logs);
            verify(mockActiveOrderRepo, times(3)).unlock(any());
        }
    }

    private static void assertUnlockFailureLoggedAtWarn(LogCapture logs) {
        List<String> warnThrowables = logs.throwableClassNamesAt(Level.WARN);
        assertTrue(warnThrowables.contains(IllegalMonitorStateException.class.getName()),
                "a failed order unlock must be logged at WARN with its exception, but WARN carried "
                        + warnThrowables);
    }

    // ---- G2: a lost race tells the buyer to try again, and the failure path tells the truth (#187) ----

    private static final String CART_CHANGED = "Your cart changed while you were paying. Please try again.";
    private static final String PLACES_TAKEN =
            "These places were taken while you were paying. Any charge is being refunded; please choose again.";
    private static final String NOTHING_CHANGED = "Checkout failed, nothing was changed";

    // CheckoutPresenter shows a DomainException's message, so the buyer reads why and what to do. A race lost
    // after the freeze rolls the checkout back: the holds are released, the cart is cleared and the charge is
    // refunded, so "try again" would send the buyer to an empty cart.
    @Test
    void GivenPhase3LosesEveryVersionRace_WhenCheckoutMember_ThenTheBuyerIsToldThePlacesWereTakenAndToChooseAgain() {
        arrangeOneStandingLineCheckout();
        doThrow(new ObjectOptimisticLockingFailureException(StandingZone.class, ZONE_ID_1))
                .when(mockOrderReceiptRepo).save(any());

        RuntimeException failed = assertThrows(RuntimeException.class,
                () -> checkoutService.checkoutMember(VALID_TOKEN, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN));

        assertTheBuyerIsToldThePlacesWereTaken(failed);
    }

    @Test
    void GivenPhase3LosesEveryVersionRace_WhenCheckoutGuest_ThenTheBuyerIsToldThePlacesWereTakenAndToChooseAgain() {
        arrangeGuestStandingCart();
        givenSuccessfulPaymentAndIssuanceWithFlagGuest(1);
        doThrow(new ObjectOptimisticLockingFailureException(StandingZone.class, ZONE_ID_1))
                .when(mockOrderReceiptRepo).save(any());

        RuntimeException failed = assertThrows(RuntimeException.class, () -> checkoutService.checkoutGuest(
                VALID_GUEST_SID, GUEST_EMAIL, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN, BUYER_AGE));

        assertTheBuyerIsToldThePlacesWereTaken(failed);
    }

    private static void assertTheBuyerIsToldThePlacesWereTaken(RuntimeException failed) {
        ConcurrentReservationException cause = assertInstanceOf(ConcurrentReservationException.class,
                failed.getCause(), "a race lost after the freeze must reach the buyer as a concurrent change");
        assertEquals(PLACES_TAKEN, cause.getMessage(),
                "the checkout rolled back and its cart is gone, so the buyer must not be told to try again");
    }

    @Test
    void GivenFreezeSaveLosesAVersionRace_WhenCheckoutMember_ThenTheFailureSaysNothingChangedAndToTryAgain() {
        arrangeMemberStandingCart();
        String orderKey = mockActiveOrderRepo.getByUserId(USER_ID).getOrderKey();
        loseTheFreezeSave(orderKey);

        try (LogCapture logs = LogCapture.of(CheckoutService.class)) {
            RuntimeException failed = assertThrows(RuntimeException.class, () ->
                    checkoutService.checkoutMember(VALID_TOKEN, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN));

            assertNothingChangedAndCartChanged(failed);
            List<String> info = logs.messagesAt(Level.INFO);
            assertTrue(info.stream().anyMatch(message -> message.contains("changed nothing")),
                    "the skipped rollback must say the failed freeze changed nothing, but INFO carried " + info);
        }
    }

    @Test
    void GivenFreezeSaveLosesAVersionRace_WhenCheckoutGuest_ThenTheFailureSaysNothingChangedAndToTryAgain() {
        arrangeGuestStandingCart();
        String orderKey = mockActiveOrderRepo.getBySessionId(VALID_GUEST_SID).orElseThrow().getOrderKey();
        loseTheFreezeSave(orderKey);

        RuntimeException failed = assertThrows(RuntimeException.class, () -> checkoutService.checkoutGuest(
                VALID_GUEST_SID, GUEST_EMAIL, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN, BUYER_AGE));

        assertNothingChangedAndCartChanged(failed);
    }

    // The rollback's own unlock runs before the refund: if it fails, the refund must still happen.
    @Test
    void GivenRollbackUnlockFails_WhenCheckoutFailsAfterTheCharge_ThenTheChargeIsStillRefunded() {
        arrangeMemberStandingCart();
        givenSuccessfulPaymentAndIssuanceWithFlag(1);
        when(mockTicketIssuer.issue(any())).thenThrow(new TicketIssuanceFailedException("rejected by the issuer"));
        doNothing().doThrow(new IllegalMonitorStateException("order lock not held")).doNothing()
                .when(mockActiveOrderRepo).unlock(any());

        try (LogCapture logs = LogCapture.of(CheckoutService.class)) {
            RuntimeException failed = assertThrows(RuntimeException.class, () ->
                    checkoutService.checkoutMember(VALID_TOKEN, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN));

            assertInstanceOf(TicketIssuanceFailedException.class, failed.getCause(),
                    "a failed unlock must not replace the checkout's own failure");
            verify(mockPaymentGateway).refund(eq(PAYMENT_TRANSACTION_ID), anyDouble());
            assertUnlockFailureLoggedAtWarn(logs);
            verify(mockActiveOrderRepo, times(3)).unlock(any());
        }
    }

    private void loseTheFreezeSave(String orderKey) {
        doAnswer(invocation -> {
            if (invocation.<ActiveOrder>getArgument(0).isCheckoutInProgress()) {
                throw new ObjectOptimisticLockingFailureException(ActiveOrder.class, orderKey);
            }
            return null;
        }).when(mockActiveOrderRepo).save(any());
    }

    private static void assertNothingChangedAndCartChanged(RuntimeException failed) {
        assertEquals(NOTHING_CHANGED, failed.getMessage(),
                "the attempt's freeze save failed, so it changed nothing and returned nothing to stock");
        ConcurrentReservationException cause = assertInstanceOf(ConcurrentReservationException.class,
                failed.getCause(), "a lost freeze must reach the buyer as a concurrent change they can retry");
        assertEquals(CART_CHANGED, cause.getMessage());
    }
}
