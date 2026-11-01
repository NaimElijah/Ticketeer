package com.ticketing.system.unit.application;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import ch.qos.logback.classic.Level;

import com.ticketing.system.testutil.CompanyRepositories;
import com.ticketing.system.shared.dto.BarcodeDTO;
import com.ticketing.system.testutil.LogCapture;
import com.ticketing.system.testutil.TestTransactions;
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
import com.ticketing.system.identity.application.port.out.SessionManager;
import com.ticketing.system.sales.application.port.out.TicketIssuer;
import com.ticketing.system.sales.application.service.CheckoutService;
import com.ticketing.system.sales.application.port.out.MarketGate;
import com.ticketing.system.shared.exception.AuthenticationFailedException;
import com.ticketing.system.shared.exception.ConcurrentReservationException;
import com.ticketing.system.shared.exception.EventNotFoundException;
import com.ticketing.system.shared.exception.ExternalServiceUnavailableException;
import com.ticketing.system.shared.exception.InvalidStateTransitionException;
import com.ticketing.system.shared.exception.MarketNotOpenException;
import com.ticketing.system.shared.exception.PaymentGatewayException;
import com.ticketing.system.shared.exception.PaymentGatewayUnreachableException;
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

    private ActiveOrder mockOrder;
    private CartLineItem itemEvent1Zone1;
    private CartLineItem itemEvent1Zone2;
    private CartLineItem itemEvent2Zone3;
    private Event event1;
    private Event event2;

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
        // onto the SAME mock event store (and a mock company port for policy), so the existing
        // event/zone/seat stubs still drive confirm/validate/price/policy one layer down. CheckoutService
        // keeps mockEventRepo for the Phase-3 buyer-lock.
        checkoutService = new CheckoutService(
                mockActiveOrderRepo,
                mockEventRepo,
                new InventoryService(mockEventRepo, CompanyRepositories.everyCompanyWithoutAPolicy(),
                        TestTransactions.noOpManager()),
                mockTicketRepo,
                mockOrderReceiptRepo,
                mockTicketIssuer,
                mockPaymentGateway,
                mockEventPublisher,
                mockiSessionManager,
                mockUserRepository,
                mockMarketGate,
                TestTransactions.noOpManager(),
                Clock.fixed(T0, ZoneOffset.UTC)
        );

        mockOrder = mock(ActiveOrder.class);

        itemEvent1Zone1 = mock(CartLineItem.class);
        itemEvent1Zone2 = mock(CartLineItem.class);
        itemEvent2Zone3 = mock(CartLineItem.class);

        event1 = mock(Event.class, RETURNS_DEEP_STUBS);
        event2 = mock(Event.class, RETURNS_DEEP_STUBS);

        when(mockiSessionManager.validateToken(VALID_TOKEN)).thenReturn(true);
        when(mockiSessionManager.extractUserId(VALID_TOKEN)).thenReturn(USER_ID);
        when(mockActiveOrderRepo.getByUserId(USER_ID)).thenReturn(mockOrder);

        when(itemEvent1Zone1.geteventId()).thenReturn(EVENT_ID_1);
        when(itemEvent1Zone1.getzoneId()).thenReturn(ZONE_ID_1);
        when(itemEvent1Zone1.getPriceAtReservation()).thenReturn(100.0);

        when(itemEvent1Zone2.geteventId()).thenReturn(EVENT_ID_1);
        when(itemEvent1Zone2.getzoneId()).thenReturn(ZONE_ID_2);
        when(itemEvent1Zone2.getPriceAtReservation()).thenReturn(150.0);

        when(itemEvent2Zone3.geteventId()).thenReturn(EVENT_ID_2);
        when(itemEvent2Zone3.getzoneId()).thenReturn(ZONE_ID_3);
        when(itemEvent2Zone3.getPriceAtReservation()).thenReturn(200.0);

        when(mockEventRepo.findById(EVENT_ID_1)).thenReturn(event1);
        when(mockEventRepo.findById(EVENT_ID_2)).thenReturn(event2);

        when(event1.getName()).thenReturn("Concert");
        when(event2.getName()).thenReturn("Theater");
        when(event1.getId()).thenReturn(EVENT_ID_1);
        when(event1.getCompanyId()).thenReturn(100);
        when(event2.getId()).thenReturn(EVENT_ID_2);
        when(event2.getCompanyId()).thenReturn(100);
        when(event1.getStatus()).thenReturn(EventStatus.ON_SALE);
        when(event2.getStatus()).thenReturn(EventStatus.ON_SALE);

        when(mockOrder.isCheckoutInProgress()).thenReturn(true);
        when(mockOrder.getOrderKey()).thenReturn("mock-order-key");
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

    @Test
    void GivenMissingToken_WhenCheckout_ThenThrowException() {
        assertThrows(RuntimeException.class, () ->
                checkoutService.checkoutMember("", IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN)
        );
    }

    @Test
    void GivenNullToken_WhenCheckout_ThenThrowException() {
        assertThrows(RuntimeException.class, () ->
                checkoutService.checkoutMember(null, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN)
        );
    }

    @Test
    void GivenInvalidToken_WhenCheckout_ThenThrowException() {
        when(mockiSessionManager.validateToken(INVALID_TOKEN)).thenReturn(false);

        assertThrows(RuntimeException.class, () ->
                checkoutService.checkoutMember(INVALID_TOKEN, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN)
        );
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
    void GivenNoActiveOrder_WhenCheckout_ThenThrowException() {
        when(mockActiveOrderRepo.getByUserId(USER_ID)).thenReturn(null);

        assertThrows(RuntimeException.class, () ->
                checkoutService.checkoutMember(VALID_TOKEN, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN)
        );
    }

    @Test
    void GivenExpiredOrderWithMultipleTickets_WhenCheckout_ThenThrowException() {
        when(mockOrder.validateCanCheckout(any())).thenReturn(false);
        when(mockOrder.ReturnToStock()).thenReturn(
                List.of(itemEvent1Zone1, itemEvent1Zone2, itemEvent2Zone3)
        );

        assertThrows(RuntimeException.class, () ->
                checkoutService.checkoutMember(VALID_TOKEN, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN)
        );
    }
    //////////////////////////////////////////////////////////added test for expired order with multiple tickets scenario after check1
@Test
void GivenExpiredOrderWithMultipleTickets_WhenCheckout_ThenClearCartAndReturnTicketsToStock() {
    ActiveOrder activeOrder = new ActiveOrder(USER_ID, NOW);
    String orderKey = activeOrder.getOrderKey();
    InventoryZone zone1 = new StandingZone(ZONE_ID_1, "VIP", 5, 100.0);
    InventoryZone zone2 = new StandingZone(ZONE_ID_2, "REGULAR", 5, 150.0);

    zone1.reserve(InventorySelection.standing(1, orderKey));
    zone2.reserve(InventorySelection.standing(1, orderKey));

    activeOrder.addStandingReservation(EVENT_ID_1, ZONE_ID_1, 1, 100.0, NOW.minusMinutes(20));
    activeOrder.addStandingReservation(EVENT_ID_1, ZONE_ID_2, 1, 150.0, NOW);

    when(mockActiveOrderRepo.getByUserId(USER_ID)).thenReturn(activeOrder);

    when(mockEventRepo.findById(EVENT_ID_1)).thenReturn(event1);

    when(event1.releaseInventory(ZONE_ID_1, InventorySelection.standing(1))).thenAnswer(invocation -> {
        InventorySelection sel = invocation.getArgument(1);
        zone1.release(sel);
        return true;
    });

    when(event1.releaseInventory(ZONE_ID_2, InventorySelection.standing(1))).thenAnswer(invocation -> {
        InventorySelection sel = invocation.getArgument(1);
        zone2.release(sel);
        return true;
    });

    assertThrows(RuntimeException.class, () ->
            checkoutService.checkoutMember(
                    VALID_TOKEN,
                    IDEMPOTENCY_KEY,
                    CURRENCY,
                    PAYMENT_METHOD_TOKEN
            )
    );

    assertEquals(0, activeOrder.countTickets(EVENT_ID_1, ZONE_ID_1, NOW));
    assertEquals(0, activeOrder.countTickets(EVENT_ID_1, ZONE_ID_2, NOW));

    assertEquals(5, zone1.getAvailableAmount());
    assertEquals(5, zone2.getAvailableAmount());
    assertEquals(0, zone1.getReservedAmount());
    assertEquals(0, zone2.getReservedAmount());
}



    @Test
    void GivenOrderCannotCheckout_WhenCheckout_ThenThrowException() {
        when(mockOrder.validateCanCheckout(any())).thenReturn(false);
        when(mockOrder.ReturnToStock()).thenReturn(List.of());

        assertThrows(RuntimeException.class, () ->
                checkoutService.checkoutMember(VALID_TOKEN, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN)
        );
    }

    @Test
void GivenOrderCannotCheckout_WhenCheckout_ThenClearCartAndReturnTicketsToStock() {
    ActiveOrder activeOrder = new ActiveOrder(USER_ID, NOW);
    String orderKey = activeOrder.getOrderKey();
    InventoryZone zone = new StandingZone(ZONE_ID_1, "VIP", 5, 100.0);
    zone.reserve(InventorySelection.standing(1, orderKey));

    activeOrder.addStandingReservation(
            EVENT_ID_1,
            ZONE_ID_1,
            1,
            100.0,
            NOW.minusMinutes(20)
    );

    when(mockActiveOrderRepo.getByUserId(USER_ID)).thenReturn(activeOrder);
    when(mockEventRepo.findById(EVENT_ID_1)).thenReturn(event1);

    when(event1.releaseInventory(ZONE_ID_1, InventorySelection.standing(1))).thenAnswer(invocation -> {
        InventorySelection sel = invocation.getArgument(1);
        zone.release(sel);
        return true;
    });

    assertThrows(RuntimeException.class, () ->
            checkoutService.checkoutMember(
                    VALID_TOKEN,
                    IDEMPOTENCY_KEY,
                    CURRENCY,
                    PAYMENT_METHOD_TOKEN
            )
    );

    assertEquals(0, activeOrder.countTickets(EVENT_ID_1, ZONE_ID_1, NOW));
    assertEquals(5, zone.getAvailableAmount());
    assertEquals(0, zone.getReservedAmount());
}

   @Test
void GivenPaymentFails_WhenCheckout_ThenClearCartAndReturnTicketsToStock() {
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
    when(mockEventRepo.findById(EVENT_ID_1)).thenReturn(event1);
    when(event1.getVenueMap().getZone(ZONE_ID_1)).thenReturn(zone);

    when(event1.calculatePrice(anyInt(), anyDouble(), any(LocalDateTime.class)))
            .thenReturn(100.0);

    when(mockPaymentGateway.charge(any(PaymentRequestDTO.class)))
            .thenReturn(null);

    when(event1.releaseInventory(ZONE_ID_1, InventorySelection.standing(1))).thenAnswer(invocation -> {
        InventorySelection sel = invocation.getArgument(1);
        zone.release(sel);
        return true;
    });

    assertThrows(RuntimeException.class, () ->
            checkoutService.checkoutMember(
                    VALID_TOKEN,
                    IDEMPOTENCY_KEY,
                    CURRENCY,
                    PAYMENT_METHOD_TOKEN
            )
    );

    assertEquals(0, activeOrder.countTickets(EVENT_ID_1, ZONE_ID_1, NOW));
    assertEquals(5, zone.getAvailableAmount());
    assertEquals(0, zone.getReservedAmount());
}

    // Regression for the non-atomic rollback (atomic checkout-failure rollback): on a Phase-2 payment failure
    // the inventory return must run UNDER the event lock, so eventRepository.save is legal (never throws the
    // "Event must be locked before saving" guard) and the loop reaches the cart clear. We assert the lock is
    // held across the save via Mockito InOrder: lockForBuyerOperation -> save -> unlockBuyerOperation.
    @Test
    void GivenPaymentFails_WhenCheckout_ThenInventoryReturnedUnderEventLock() {
        ActiveOrder activeOrder = new ActiveOrder(USER_ID, NOW);
        String orderKey = activeOrder.getOrderKey();
        InventoryZone zone = new StandingZone(ZONE_ID_1, "VIP", 5, 100.0);
        zone.reserve(InventorySelection.standing(1, orderKey));
        activeOrder.addStandingReservation(EVENT_ID_1, ZONE_ID_1, 1, 100.0, NOW);

        when(mockActiveOrderRepo.getByUserId(USER_ID)).thenReturn(activeOrder);
        when(mockEventRepo.findById(EVENT_ID_1)).thenReturn(event1);
        when(event1.getVenueMap().getZone(ZONE_ID_1)).thenReturn(zone);
        when(event1.calculatePrice(anyInt(), anyDouble(), any(LocalDateTime.class))).thenReturn(100.0);
        when(mockPaymentGateway.charge(any(PaymentRequestDTO.class))).thenReturn(null);   // Phase-2 payment failure
        when(event1.releaseInventory(ZONE_ID_1, InventorySelection.standing(1))).thenAnswer(invocation -> {
            zone.release(invocation.getArgument(1));
            return true;
        });

        assertThrows(RuntimeException.class, () ->
                checkoutService.checkoutMember(VALID_TOKEN, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN));

        // Inventory + cart were rolled back...
        assertEquals(0, activeOrder.countTickets(EVENT_ID_1, ZONE_ID_1, NOW));
        assertEquals(5, zone.getAvailableAmount());

        // ...with the event lock held across the save (and released after), proving the save can't hit the
        // "must be locked" guard and the loop reaches the cart clear.
        InOrder inOrder = inOrder(mockEventRepo);
        inOrder.verify(mockEventRepo).lockForBuyerOperation(EVENT_ID_1);
        inOrder.verify(mockEventRepo).save(event1);
        inOrder.verify(mockEventRepo).unlockBuyerOperation(EVENT_ID_1);
    }

    @Test
void GivenTicketIssuanceFailsAfterPayment_WhenCheckout_ThenClearCartAndReturnTicketsToStock() {
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

    when(mockEventRepo.findById(EVENT_ID_1)).thenReturn(event1);
    when(event1.getVenueMap().getZone(ZONE_ID_1)).thenReturn(zone);

    when(event1.calculatePrice(anyInt(), anyDouble(), any(LocalDateTime.class)))
            .thenReturn(100.0);

    when(mockPaymentGateway.charge(any(PaymentRequestDTO.class)))
            .thenReturn(paymentResult);

    when(mockTicketIssuer.issue(any(IssuanceRequestDTO.class)))
            .thenReturn(null);

    when(event1.releaseInventory(ZONE_ID_1, InventorySelection.standing(1))).thenAnswer(invocation -> {
        InventorySelection sel = invocation.getArgument(1);
        zone.release(sel);
        return true;
    });

    assertThrows(RuntimeException.class, () ->
            checkoutService.checkoutMember(
                    VALID_TOKEN,
                    IDEMPOTENCY_KEY,
                    CURRENCY,
                    PAYMENT_METHOD_TOKEN
            )
    );

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

    when(mockEventRepo.findById(EVENT_ID_1)).thenReturn(event1);
    when(event1.getVenueMap().getZone(ZONE_ID_1)).thenReturn(zone);

    when(event1.calculatePrice(anyInt(), anyDouble(), any(LocalDateTime.class)))
            .thenReturn(100.0);

    when(mockPaymentGateway.charge(any(PaymentRequestDTO.class)))
            .thenReturn(paymentResult);

    when(mockTicketIssuer.issue(any(IssuanceRequestDTO.class)))
            .thenReturn(issuanceResult);

    when(event1.releaseInventory(ZONE_ID_1, InventorySelection.standing(1))).thenAnswer(invocation -> {
        InventorySelection sel = invocation.getArgument(1);
        zone.release(sel);
        return true;
    });

    assertThrows(RuntimeException.class, () ->
            checkoutService.checkoutMember(
                    VALID_TOKEN,
                    IDEMPOTENCY_KEY,
                    CURRENCY,
                    PAYMENT_METHOD_TOKEN
            )
    );

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

    when(mockEventRepo.findById(EVENT_ID_1)).thenReturn(event1);
    when(event1.getVenueMap().getZone(ZONE_ID_1)).thenReturn(zone);

    when(event1.calculatePrice(anyInt(), anyDouble(), any(LocalDateTime.class)))
            .thenReturn(100.0);

    when(mockPaymentGateway.charge(any(PaymentRequestDTO.class)))
            .thenReturn(paymentResult);

    when(mockTicketIssuer.issue(any(IssuanceRequestDTO.class)))
            .thenReturn(issuanceResult);

    when(event1.releaseInventory(ZONE_ID_1, InventorySelection.standing(1))).thenAnswer(invocation -> {
        InventorySelection sel = invocation.getArgument(1);
        zone.release(sel);
        return true;
    });

    assertThrows(RuntimeException.class, () ->
            checkoutService.checkoutMember(
                    VALID_TOKEN,
                    IDEMPOTENCY_KEY,
                    CURRENCY,
                    PAYMENT_METHOD_TOKEN
            )
    );

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

    when(mockEventRepo.findById(EVENT_ID_1)).thenReturn(event1);
    when(mockEventRepo.findById(EVENT_ID_2)).thenReturn(event2);

    when(event1.getVenueMap().getZone(ZONE_ID_1)).thenReturn(zone1);
    when(event1.getVenueMap().getZone(ZONE_ID_2)).thenReturn(zone2);
    when(event2.getVenueMap().getZone(ZONE_ID_3)).thenReturn(zone3);

    when(event1.calculatePrice(anyInt(), anyDouble(), any(LocalDateTime.class)))
            .thenReturn(250.0);
    when(event2.calculatePrice(anyInt(), anyDouble(), any(LocalDateTime.class)))
            .thenReturn(200.0);

    when(mockPaymentGateway.charge(any(PaymentRequestDTO.class)))
            .thenReturn(paymentResult);

    when(mockTicketIssuer.issue(any(IssuanceRequestDTO.class)))
            .thenReturn(issuanceResult);

    when(event1.releaseInventory(ZONE_ID_1, InventorySelection.standing(1))).thenAnswer(invocation -> {
        InventorySelection sel = invocation.getArgument(1);
        zone1.release(sel);
        return true;
    });

    when(event1.releaseInventory(ZONE_ID_2, InventorySelection.standing(1))).thenAnswer(invocation -> {
        InventorySelection sel = invocation.getArgument(1);
        zone2.release(sel);
        return true;
    });

    when(event2.releaseInventory(ZONE_ID_3, InventorySelection.standing(1))).thenAnswer(invocation -> {
        InventorySelection sel = invocation.getArgument(1);
        zone3.release(sel);
        return true;
    });

    assertThrows(RuntimeException.class, () ->
            checkoutService.checkoutMember(
                    VALID_TOKEN,
                    IDEMPOTENCY_KEY,
                    CURRENCY,
                    PAYMENT_METHOD_TOKEN
            )
    );

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
   @Test
void GivenPaymentRefundFails_WhenCheckout_ThenClearCartReturnTicketsToStockAndThrowRefundException() {
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

    when(mockEventRepo.findById(EVENT_ID_1)).thenReturn(event1);
    when(event1.getVenueMap().getZone(ZONE_ID_1)).thenReturn(zone);

    when(event1.calculatePrice(anyInt(), anyDouble(), any(LocalDateTime.class)))
            .thenReturn(100.0);

    when(mockPaymentGateway.charge(any(PaymentRequestDTO.class)))
            .thenReturn(paymentResult);

    when(mockTicketIssuer.issue(any(IssuanceRequestDTO.class)))
            .thenReturn(null);

    when(event1.releaseInventory(ZONE_ID_1, InventorySelection.standing(1))).thenAnswer(invocation -> {
        InventorySelection sel = invocation.getArgument(1);
        zone.release(sel);
        return true;
    });

    doThrow(new RuntimeException("refund failed"))
            .when(mockPaymentGateway)
            .refund(PAYMENT_TRANSACTION_ID, 100.0);

    assertThrows(RuntimeException.class, () ->
            checkoutService.checkoutMember(
                    VALID_TOKEN,
                    IDEMPOTENCY_KEY,
                    CURRENCY,
                    PAYMENT_METHOD_TOKEN
            )
    );

    assertEquals(0, activeOrder.countTickets(EVENT_ID_1, ZONE_ID_1, NOW));
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

    when(mockEventRepo.findById(EVENT_ID_1)).thenReturn(event1);
    when(mockEventRepo.findById(EVENT_ID_2)).thenThrow(new EventNotFoundException(EVENT_ID_2));

    when(event1.getVenueMap().getZone(ZONE_ID_1)).thenReturn(zone1);

    when(event1.releaseInventory(ZONE_ID_1, InventorySelection.standing(1))).thenAnswer(invocation -> {
        InventorySelection sel = invocation.getArgument(1);
        zone1.release(sel);
        return true;
    });

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
    when(mockOrder.validateCanCheckout(any())).thenReturn(true);
    when(mockOrder.getItems()).thenReturn(List.of(itemEvent1Zone1));
    when(mockOrder.buy()).thenReturn(List.of(itemEvent1Zone1));
    when(event1.calculatePrice(anyInt(), anyDouble(), any(LocalDateTime.class))).thenReturn(100.0);
    when(event1.calculatePriceforoneticket(anyInt(), anyDouble(), any(LocalDateTime.class))).thenReturn(100.0);
    when(mockPaymentGateway.charge(any(PaymentRequestDTO.class))).thenReturn(
            new PaymentResultDTO(PAYMENT_TRANSACTION_ID, "gateway", 100.0, "ILS", NOW));
    when(mockTicketIssuer.issue(any(IssuanceRequestDTO.class))).thenReturn(new IssuanceResultDTO(
            ISSUANCE_TRANSACTION_ID, "issuer", NOW, List.of(new BarcodeDTO("barcode-value-1", "QR"))));
    StandingZone zone1 = new StandingZone(ZONE_ID_1, "General", 100, 100.0);
    zone1.reserve(InventorySelection.standing(1));
    when(event1.getVenueMap().getZone(ZONE_ID_1)).thenReturn(zone1);
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

    when(mockOrder.validateCanCheckout(any())).thenReturn(true);
    when(mockOrder.getItems()).thenReturn(List.of(itemEvent1Zone1));
    when(mockOrder.buy()).thenReturn(List.of(itemEvent1Zone1));

    when(event1.calculatePrice(anyInt(), anyDouble(), any(LocalDateTime.class)))
            .thenReturn(100.0);

    when(event1.calculatePriceforoneticket(anyInt(), anyDouble(), any(LocalDateTime.class)))
            .thenReturn(100.0);

    when(mockPaymentGateway.charge(any(PaymentRequestDTO.class)))
            .thenReturn(paymentResult);

    when(mockTicketIssuer.issue(any(IssuanceRequestDTO.class)))
            .thenReturn(issuanceResult);

    StandingZone zone1 = new StandingZone(ZONE_ID_1, "General", 100, 100.0);
    zone1.reserve(InventorySelection.standing(1));
    when(event1.getVenueMap().getZone(ZONE_ID_1)).thenReturn(zone1);

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

    when(mockOrder.validateCanCheckout(any())).thenReturn(true);
    when(mockOrder.getItems()).thenReturn(List.of(itemEvent1Zone2));
    when(mockOrder.buy()).thenReturn(List.of(itemEvent1Zone2));

    when(event1.calculatePrice(anyInt(), anyDouble(), any(LocalDateTime.class)))
            .thenReturn(150.0);

    when(event1.calculatePriceforoneticket(anyInt(), anyDouble(), any(LocalDateTime.class)))
            .thenReturn(150.0);

    when(mockPaymentGateway.charge(any(PaymentRequestDTO.class)))
            .thenReturn(paymentResult);

    when(mockTicketIssuer.issue(any(IssuanceRequestDTO.class)))
            .thenReturn(issuanceResult);

    StandingZone zone2 = new StandingZone(ZONE_ID_2, "General", 100, 150.0);
    zone2.reserve(InventorySelection.standing(1));
    when(event1.getVenueMap().getZone(ZONE_ID_2)).thenReturn(zone2);

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

    when(mockOrder.validateCanCheckout(any())).thenReturn(true);
    when(mockOrder.getItems()).thenReturn(List.of(itemEvent1Zone1, itemEvent1Zone2));
    when(mockOrder.buy()).thenReturn(List.of(itemEvent1Zone1, itemEvent1Zone2));

    when(event1.calculatePrice(anyInt(), anyDouble(), any(LocalDateTime.class)))
            .thenReturn(250.0);

    when(event1.calculatePriceforoneticket(anyInt(), anyDouble(), any(LocalDateTime.class)))
            .thenReturn(125.0);

    when(mockPaymentGateway.charge(any(PaymentRequestDTO.class)))
            .thenReturn(paymentResult);

    when(mockTicketIssuer.issue(any(IssuanceRequestDTO.class)))
            .thenReturn(issuanceResult);

    StandingZone zone1 = new StandingZone(ZONE_ID_1, "General", 100, 100.0);
    zone1.reserve(InventorySelection.standing(1));
    when(event1.getVenueMap().getZone(ZONE_ID_1)).thenReturn(zone1);
    StandingZone zone2 = new StandingZone(ZONE_ID_2, "General", 100, 150.0);
    zone2.reserve(InventorySelection.standing(1));
    when(event1.getVenueMap().getZone(ZONE_ID_2)).thenReturn(zone2);

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
        when(mockOrder.validateCanCheckout(any())).thenReturn(true);
        when(mockOrder.getItems()).thenReturn(List.of(itemEvent1Zone1, itemEvent1Zone2));
        when(mockOrder.buy()).thenReturn(List.of(itemEvent1Zone1, itemEvent1Zone2));
        when(event1.calculatePrice(anyInt(), anyDouble(), any(LocalDateTime.class))).thenReturn(250.0);
        when(event1.calculatePriceforoneticket(anyInt(), anyDouble(), any(LocalDateTime.class))).thenReturn(125.0);
        when(mockPaymentGateway.charge(any(PaymentRequestDTO.class))).thenReturn(paymentResult);
        when(mockTicketIssuer.issue(any(IssuanceRequestDTO.class))).thenReturn(issuanceResult);
        StandingZone zone1 = new StandingZone(ZONE_ID_1, "General", 100, 100.0);
        zone1.reserve(InventorySelection.standing(1));
        when(event1.getVenueMap().getZone(ZONE_ID_1)).thenReturn(zone1);
        StandingZone zone2 = new StandingZone(ZONE_ID_2, "General", 100, 150.0);
        zone2.reserve(InventorySelection.standing(1));
        when(event1.getVenueMap().getZone(ZONE_ID_2)).thenReturn(zone2);

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
        when(mockOrder.validateCanCheckout(any())).thenReturn(true);
        when(mockOrder.getItems()).thenReturn(List.of(itemEvent1Zone1, itemEvent1Zone2));
        when(mockOrder.buy()).thenReturn(List.of(itemEvent1Zone1, itemEvent1Zone2));
        when(event1.calculatePrice(anyInt(), anyDouble(), any(LocalDateTime.class))).thenReturn(250.0);
        when(event1.calculatePriceforoneticket(anyInt(), anyDouble(), any(LocalDateTime.class))).thenReturn(125.0);
        when(mockPaymentGateway.charge(any(PaymentRequestDTO.class))).thenReturn(paymentResult);
        when(mockTicketIssuer.issue(any(IssuanceRequestDTO.class))).thenReturn(issuanceResult);
        StandingZone zone1 = new StandingZone(ZONE_ID_1, "General", 100, 100.0);
        zone1.reserve(InventorySelection.standing(1));
        when(event1.getVenueMap().getZone(ZONE_ID_1)).thenReturn(zone1);
        StandingZone zone2 = new StandingZone(ZONE_ID_2, "General", 100, 150.0);
        zone2.reserve(InventorySelection.standing(1));
        when(event1.getVenueMap().getZone(ZONE_ID_2)).thenReturn(zone2);

        checkoutService.checkoutMember(VALID_TOKEN, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN);

        ArgumentCaptor<OrderReceipt> receiptCaptor = ArgumentCaptor.forClass(OrderReceipt.class);
        verify(mockOrderReceiptRepo).save(receiptCaptor.capture());
        assertEquals(NOW, receiptCaptor.getValue().getPurchaseTime());
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

           when(mockOrder.validateCanCheckout(any())).thenReturn(true);
           when(mockOrder.getItems()).thenReturn(List.of(itemEvent1Zone1, itemEvent2Zone3));
           when(mockOrder.buy()).thenReturn(List.of(itemEvent1Zone1, itemEvent2Zone3));

           when(event1.calculatePrice(anyInt(), anyDouble(), any(LocalDateTime.class)))
                           .thenReturn(100.0);

           when(event2.calculatePrice(anyInt(), anyDouble(), any(LocalDateTime.class)))
                           .thenReturn(200.0);

           when(event1.calculatePriceforoneticket(anyInt(), anyDouble(), any(LocalDateTime.class)))
                           .thenReturn(100.0);

           when(event2.calculatePriceforoneticket(anyInt(), anyDouble(), any(LocalDateTime.class)))
                           .thenReturn(200.0);

           when(mockPaymentGateway.charge(any(PaymentRequestDTO.class)))
                           .thenReturn(paymentResult);

           when(mockTicketIssuer.issue(any(IssuanceRequestDTO.class)))
                           .thenReturn(issuanceResult);

           StandingZone zone1 = new StandingZone(ZONE_ID_1, "General", 100, 100.0);
           zone1.reserve(InventorySelection.standing(1));
           when(event1.getVenueMap().getZone(ZONE_ID_1)).thenReturn(zone1);
           StandingZone zone3 = new StandingZone(ZONE_ID_3, "General", 100, 200.0);
           zone3.reserve(InventorySelection.standing(1));
           when(event2.getVenueMap().getZone(ZONE_ID_3)).thenReturn(zone3);

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
                CartLineItem seatedItem = mock(CartLineItem.class);

                when(seatedItem.geteventId()).thenReturn(EVENT_ID_1);
                when(seatedItem.getzoneId()).thenReturn(ZONE_ID_1);
                when(seatedItem.getSeatNumber()).thenReturn("A1");
                when(seatedItem.getPriceAtReservation()).thenReturn(120.0);

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

                when(mockOrder.validateCanCheckout(any())).thenReturn(true);
                when(mockOrder.getItems()).thenReturn(List.of(seatedItem));
                when(mockOrder.buy()).thenReturn(List.of(seatedItem));

                when(event1.calculatePrice(anyInt(), anyDouble(), any(LocalDateTime.class)))
                                .thenReturn(120.0);

                when(event1.calculatePriceforoneticket(anyInt(), anyDouble(), any(LocalDateTime.class)))
                                .thenReturn(120.0);

                when(mockPaymentGateway.charge(any(PaymentRequestDTO.class))).thenReturn(paymentResult);
                when(mockTicketIssuer.issue(any(IssuanceRequestDTO.class))).thenReturn(issuanceResult);

                SeatedZone seatedZoneA1 = new SeatedZone(ZONE_ID_1, "Orchestra", 120.0, List.of(new Seat("A1", 0, 0)));
                seatedZoneA1.reserve(InventorySelection.seated(List.of("A1"), "mock-order-key"));
                when(event1.getVenueMap().getZone(ZONE_ID_1)).thenReturn(seatedZoneA1);

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

                when(itemEvent1Zone1.getSeatNumber()).thenReturn(null);

                when(mockOrder.validateCanCheckout(any())).thenReturn(true);
                when(mockOrder.getItems()).thenReturn(List.of(itemEvent1Zone1));
                when(mockOrder.buy()).thenReturn(List.of(itemEvent1Zone1));

                when(event1.calculatePrice(anyInt(), anyDouble(), any(LocalDateTime.class)))
                                .thenReturn(100.0);

                when(event1.calculatePriceforoneticket(anyInt(), anyDouble(), any(LocalDateTime.class)))
                                .thenReturn(100.0);

                when(mockPaymentGateway.charge(any(PaymentRequestDTO.class))).thenReturn(paymentResult);
                when(mockTicketIssuer.issue(any(IssuanceRequestDTO.class))).thenReturn(issuanceResult);

                StandingZone standingZone1 = new StandingZone(ZONE_ID_1, "General", 100, 100.0);
                standingZone1.reserve(InventorySelection.standing(1));
                when(event1.getVenueMap().getZone(ZONE_ID_1)).thenReturn(standingZone1);

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
                when(mockTicketIssuer.issue(any(IssuanceRequestDTO.class))).thenReturn(null);

                assertThrows(RuntimeException.class, () -> checkoutService.checkoutMember(VALID_TOKEN, IDEMPOTENCY_KEY,
                                CURRENCY, PAYMENT_METHOD_TOKEN));

                assertEquals(SeatStatus.AVAILABLE, seatedZone.getSeatStatus("A1"));
                assertTrue(activeOrder.isEmpty());
        }
        
        

        @Test
        void GivenMixedStandingAndSeatedOrder_WhenCheckout_ThenAllTicketsSavedWithCorrectSeatNumbers() {
                CartLineItem standingItem = mock(CartLineItem.class);
                CartLineItem seatedItem = mock(CartLineItem.class);

                when(standingItem.geteventId()).thenReturn(EVENT_ID_1);
                when(standingItem.getzoneId()).thenReturn(ZONE_ID_1);
                when(standingItem.getSeatNumber()).thenReturn(null);
                when(standingItem.getPriceAtReservation()).thenReturn(50.0);

                when(seatedItem.geteventId()).thenReturn(EVENT_ID_1);
                when(seatedItem.getzoneId()).thenReturn(ZONE_ID_2);
                when(seatedItem.getSeatNumber()).thenReturn("B7");
                when(seatedItem.getPriceAtReservation()).thenReturn(120.0);

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

                when(mockOrder.validateCanCheckout(any())).thenReturn(true);
                when(mockOrder.getItems()).thenReturn(List.of(standingItem, seatedItem));
                when(mockOrder.buy()).thenReturn(List.of(standingItem, seatedItem));

                when(event1.calculatePrice(anyInt(), anyDouble(), any(LocalDateTime.class)))
                        .thenAnswer(invocation -> {
                                int quantity = invocation.getArgument(0);
                                double price = invocation.getArgument(1);
                                return quantity * price;
                        });

                when(event1.calculatePriceforoneticket(anyInt(), anyDouble(), any(LocalDateTime.class)))
                        .thenAnswer(invocation -> invocation.getArgument(1));

                when(mockPaymentGateway.charge(any(PaymentRequestDTO.class))).thenReturn(paymentResult);
                when(mockTicketIssuer.issue(any(IssuanceRequestDTO.class))).thenReturn(issuanceResult);

                StandingZone standingZone = new StandingZone(ZONE_ID_1, "Standing", 100, 50.0);
                standingZone.reserve(InventorySelection.standing(1));

                SeatedZone seatedZone = new SeatedZone(ZONE_ID_2, "Seated", 120.0, List.of(new Seat("B7", 0, 0)));
                seatedZone.reserve(InventorySelection.seated(List.of("B7"), "mock-order-key"));

                when(event1.getVenueMap().getZone(ZONE_ID_1)).thenReturn(standingZone);
                when(event1.getVenueMap().getZone(ZONE_ID_2)).thenReturn(seatedZone);

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
        }

        // Fix #3: a Phase-2 failure rollback over a multi-event cart must release every event
        // independently. If one event's release throws, the remaining events must still be released
        // and the cart must still be cleared — the rollback loop no longer aborts on the first throw.
        @Test
        void GivenMultiEventRollbackWhereOneReleaseFails_WhenCheckout_ThenOtherEventsStillReleasedAndCartCleared() {
                ActiveOrder activeOrder = new ActiveOrder(USER_ID, NOW);
                String orderKey = activeOrder.getOrderKey();

                // Event B is a real standing zone holding a live reservation under this order.
                StandingZone zoneB = new StandingZone(ZONE_ID_3, "GA", 5, 200.0);
                zoneB.reserve(InventorySelection.standing(1, orderKey));

                activeOrder.addStandingReservation(EVENT_ID_1, ZONE_ID_1, 1, 100.0, NOW);
                activeOrder.addStandingReservation(EVENT_ID_2, ZONE_ID_3, 1, 200.0, NOW);

                when(mockActiveOrderRepo.getByUserId(USER_ID)).thenReturn(activeOrder);
                when(mockEventRepo.findById(EVENT_ID_1)).thenReturn(event1);
                when(mockEventRepo.findById(EVENT_ID_2)).thenReturn(event2);

                // Event A's release fails; event B's release delegates to the real zone.
                when(event1.releaseInventory(anyInt(), any(InventorySelection.class)))
                        .thenThrow(new IllegalStateException("event A release failed"));
                when(event2.releaseInventory(anyInt(), any(InventorySelection.class))).thenAnswer(invocation -> {
                        zoneB.release(invocation.getArgument(1));
                        return true;
                });

                // Force a Phase-2 failure (before any inventory is confirmed) so the rollback runs.
                when(mockPaymentGateway.charge(any(PaymentRequestDTO.class))).thenReturn(null);

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
void GivenUserTooYoungForAgePolicy_WhenCheckout_ThenThrowExceptionAndPaymentNotCharged_NoReceiptNoTicket_AndTicketReturnedToStock() {
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
                noDiscountPolicy()
        );
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
    void GivenNullGuestSessionId_WhenGuestCheckout_ThenThrowException() {
        assertThrows(RuntimeException.class, () ->
                checkoutService.checkoutGuest(null, GUEST_EMAIL, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN, BUYER_AGE)
        );
    }

    @Test
    void GivenBlankGuestSessionId_WhenGuestCheckout_ThenThrowException() {
        assertThrows(RuntimeException.class, () ->
                checkoutService.checkoutGuest("  ", GUEST_EMAIL, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN, BUYER_AGE)
        );
    }

    @Test
    void GivenInvalidGuestSession_WhenGuestCheckout_ThenThrowException() {
        givenInvalidGuestSession();
        assertThrows(RuntimeException.class, () ->
                checkoutService.checkoutGuest(INVALID_GUEST_SID, GUEST_EMAIL, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN, BUYER_AGE)
        );
    }

    @Test
    void GivenNullGuestEmail_WhenGuestCheckout_ThenThrowException() {
        givenValidGuestSession();
        assertThrows(RuntimeException.class, () ->
                checkoutService.checkoutGuest(VALID_GUEST_SID, null, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN, BUYER_AGE)
        );
    }

    @Test
    void GivenBlankGuestEmail_WhenGuestCheckout_ThenThrowException() {
        givenValidGuestSession();
        assertThrows(RuntimeException.class, () ->
                checkoutService.checkoutGuest(VALID_GUEST_SID, "  ", IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN, BUYER_AGE)
        );
    }

  

    @Test
    void GivenNullIdempotencyKey_WhenGuestCheckout_ThenThrowException() {
        givenValidGuestSession();
        assertThrows(RuntimeException.class, () ->
                checkoutService.checkoutGuest(VALID_GUEST_SID, GUEST_EMAIL, null, CURRENCY, PAYMENT_METHOD_TOKEN, BUYER_AGE)
        );
    }

    @Test
    void GivenNullCurrency_WhenGuestCheckout_ThenThrowException() {
        givenValidGuestSession();
        assertThrows(RuntimeException.class, () ->
                checkoutService.checkoutGuest(VALID_GUEST_SID, GUEST_EMAIL, IDEMPOTENCY_KEY, null, PAYMENT_METHOD_TOKEN, BUYER_AGE)
        );
    }

    @Test
    void GivenNullPaymentMethodToken_WhenGuestCheckout_ThenThrowException() {
        givenValidGuestSession();
        assertThrows(RuntimeException.class, () ->
                checkoutService.checkoutGuest(VALID_GUEST_SID, GUEST_EMAIL, IDEMPOTENCY_KEY, CURRENCY, null, BUYER_AGE)
        );
    }

    // --- Order state ---

    @Test
    void GivenNoGuestActiveOrder_WhenGuestCheckout_ThenThrowException() {
        givenValidGuestSession();
        givenNoGuestOrder();
        assertThrows(RuntimeException.class, () ->
                checkoutService.checkoutGuest(VALID_GUEST_SID, GUEST_EMAIL, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN, BUYER_AGE)
        );
    }

    @Test
    void GivenGuestOrderCannotCheckout_WhenGuestCheckout_ThenThrowExceptionAndReturnTicketsToStock() {
        givenValidGuestSession();

        StandingZone zone = new StandingZone(ZONE_ID_1, "VIP", 5, 100.0);

        ActiveOrder activeOrder = ActiveOrder.forGuest(VALID_GUEST_SID, NOW);
        activeOrder.addStandingReservation(EVENT_ID_1, ZONE_ID_1, 1, 100.0, NOW.minusMinutes(20));
        zone.reserve(InventorySelection.standing(1, activeOrder.getOrderKey()));

        when(mockActiveOrderRepo.getBySessionId(VALID_GUEST_SID)).thenReturn(Optional.of(activeOrder));
        when(mockEventRepo.findById(EVENT_ID_1)).thenAnswer(inv ->
                createRealEventWithPolicyAndZone(EVENT_ID_1, zone, new NoPurchasePolicy()));

        assertThrows(RuntimeException.class, () ->
                checkoutService.checkoutGuest(VALID_GUEST_SID, GUEST_EMAIL, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN, BUYER_AGE)
        );

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
        when(mockPaymentGateway.charge(any())).thenReturn(null);

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
        when(mockPaymentGateway.charge(any())).thenReturn(null);

        try (LogCapture logs = LogCapture.of(CheckoutService.class)) {
            assertThrows(RuntimeException.class, () -> checkoutService.checkoutGuest(
                    VALID_GUEST_SID, GUEST_EMAIL, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN, BUYER_AGE));

            assertEquals(List.of(), logs.eventsContaining(VALID_GUEST_SID),
                    "a failed guest checkout must not log the guest's session id");
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
        when(mockTicketIssuer.issue(any())).thenReturn(null);

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
        verify(mockTicketRepo, times(1)).save(any(Ticket.class));
        verify(mockOrderReceiptRepo, times(1)).save(any(OrderReceipt.class));
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

        when(mockActiveOrderRepo.getByUserId(USER_ID)).thenReturn(activeOrder);
        when(mockEventRepo.findById(EVENT_ID_1)).thenReturn(event1);
        when(mockEventRepo.findById(EVENT_ID_2)).thenReturn(event2);
        when(event1.getVenueMap().getZone(ZONE_ID_1)).thenReturn(zone1);
        when(event2.getVenueMap().getZone(ZONE_ID_3)).thenReturn(zone3);
        when(event1.calculatePriceforoneticket(anyInt(), anyDouble(), any(LocalDateTime.class))).thenReturn(100.0);
        when(event2.calculatePriceforoneticket(anyInt(), anyDouble(), any(LocalDateTime.class))).thenReturn(200.0);

        PaymentResultDTO paymentResult = new PaymentResultDTO(PAYMENT_TRANSACTION_ID, "gateway", 300.0, "ILS",
                NOW);
        when(mockPaymentGateway.charge(any(PaymentRequestDTO.class))).thenReturn(paymentResult);
        when(mockTicketIssuer.issue(any(IssuanceRequestDTO.class))).thenReturn(new IssuanceResultDTO(
                ISSUANCE_TRANSACTION_ID, "issuer", NOW,
                List.of(new BarcodeDTO("b1", "QR"), new BarcodeDTO("b2", "QR"))));

        // First confirm (whichever event the grouping visits first) commits SOLD; the second throws.
        AtomicInteger confirmCalls = new AtomicInteger();
        doAnswer(inv -> {
            if (confirmCalls.incrementAndGet() >= 2) {
                throw new RuntimeException("confirm boom");
            }
            int zoneId = inv.getArgument(0);
            InventorySelection sel = inv.getArgument(1);
            if (zoneId == ZONE_ID_1) {
                zone1.confirmSale(sel);
            } else {
                zone3.confirmSale(sel);
            }
            return null;
        }).when(event1).confirmInventorySale(anyInt(), any());
        doAnswer(inv -> {
            if (confirmCalls.incrementAndGet() >= 2) {
                throw new RuntimeException("confirm boom");
            }
            int zoneId = inv.getArgument(0);
            InventorySelection sel = inv.getArgument(1);
            if (zoneId == ZONE_ID_1) {
                zone1.confirmSale(sel);
            } else {
                zone3.confirmSale(sel);
            }
            return null;
        }).when(event2).confirmInventorySale(anyInt(), any());

        // Compensation (SOLD -> AVAILABLE) and rollback release delegate to the real zones.
        doAnswer(inv -> { zone1.returnSoldToStock(inv.getArgument(1)); return true; })
                .when(event1).returnSoldToStock(eq(ZONE_ID_1), any());
        doAnswer(inv -> { zone3.returnSoldToStock(inv.getArgument(1)); return true; })
                .when(event2).returnSoldToStock(eq(ZONE_ID_3), any());
        when(event1.releaseInventory(eq(ZONE_ID_1), any()))
                .thenAnswer(inv -> { zone1.release(inv.getArgument(1)); return true; });
        when(event2.releaseInventory(eq(ZONE_ID_3), any()))
                .thenAnswer(inv -> { zone3.release(inv.getArgument(1)); return true; });

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

        when(mockOrder.validateCanCheckout(any())).thenReturn(true);
        when(mockOrder.getItems()).thenReturn(List.of(itemEvent1Zone1));
        when(mockOrder.buy()).thenReturn(List.of(itemEvent1Zone1));
        when(event1.calculatePriceforoneticket(anyInt(), anyDouble(), any(LocalDateTime.class))).thenReturn(100.0);
        when(mockPaymentGateway.charge(any(PaymentRequestDTO.class))).thenReturn(paymentResult);
        when(mockTicketIssuer.issue(any(IssuanceRequestDTO.class))).thenReturn(issuanceResult);
        StandingZone zone1 = new StandingZone(ZONE_ID_1, "General", 100, 100.0);
        zone1.reserve(InventorySelection.standing(1));
        when(event1.getVenueMap().getZone(ZONE_ID_1)).thenReturn(zone1);

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

    // One standing ticket of a real event, held by the member's real cart.
    private void arrangeMemberStandingCart() {
        StandingZone zone = new StandingZone(ZONE_ID_1, "VIP", 5, 100.0);
        Event event = createRealEventWithPolicyAndZone(EVENT_ID_1, zone, new NoPurchasePolicy());
        ActiveOrder order = new ActiveOrder(USER_ID, NOW);
        order.addStandingReservation(EVENT_ID_1, ZONE_ID_1, 1, 100.0, NOW);
        zone.reserve(InventorySelection.standing(1, order.getOrderKey()));
        when(mockActiveOrderRepo.getByUserId(USER_ID)).thenReturn(order);
        when(mockEventRepo.findById(EVENT_ID_1)).thenReturn(event);
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
    private static final String NOTHING_CHANGED = "Checkout failed, nothing was changed";

    // CheckoutPresenter shows a DomainException's message, so the buyer reads why and what to do.
    @Test
    void GivenPhase3LosesEveryVersionRace_WhenCheckoutMember_ThenTheBuyerIsToldTheCartChangedAndToTryAgain() {
        arrangeOneStandingLineCheckout();
        doThrow(new ObjectOptimisticLockingFailureException(StandingZone.class, ZONE_ID_1))
                .when(mockOrderReceiptRepo).save(any());

        RuntimeException failed = assertThrows(RuntimeException.class,
                () -> checkoutService.checkoutMember(VALID_TOKEN, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN));

        ConcurrentReservationException cause = assertInstanceOf(ConcurrentReservationException.class,
                failed.getCause(), "a lost race must reach the buyer as a concurrent change they can retry");
        assertEquals(CART_CHANGED, cause.getMessage());
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
