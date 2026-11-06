package com.ticketing.system.unit.application;

import org.springframework.context.ApplicationEventPublisher;
import com.ticketing.system.testutil.CompanyRepositories;
import com.ticketing.system.shared.event.PurchaseCompletedNotice;
import com.ticketing.system.sales.application.port.out.TicketIssuer;
import com.ticketing.system.sales.application.port.out.PaymentGateway;
import com.ticketing.system.sales.application.port.out.TicketRepository;
import com.ticketing.system.sales.application.port.out.OrderReceiptRepository;
import com.ticketing.system.sales.application.port.out.ActiveOrderRepository;
import com.ticketing.system.catalog.application.port.out.EventRepository;
import com.ticketing.system.identity.application.port.out.SessionManager;

import com.ticketing.system.shared.dto.*;
import com.ticketing.system.sales.application.service.CheckoutService;
import com.ticketing.system.catalog.application.service.InventoryService;
import com.ticketing.system.sales.application.port.out.MarketGate;
import com.ticketing.system.sales.domain.*;
import com.ticketing.system.organization.application.port.out.ProductionCompanyRepository;
import com.ticketing.system.catalog.domain.*;
import com.ticketing.system.identity.application.port.out.UserRepository;
import com.ticketing.system.identity.domain.User;
import com.ticketing.system.sales.adapter.out.persistence.MemoryActiveOrderRepository;
import com.ticketing.system.shared.domain.policy.NoPurchasePolicy;
import com.ticketing.system.shared.exception.EventNotFoundException;
import com.ticketing.system.shared.exception.InvalidTokenException;
import com.ticketing.system.shared.exception.SessionExpiredException;
import com.ticketing.system.shared.exception.TicketIssuanceFailedException;
import com.ticketing.system.testutil.TestTransactions;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * {@link CheckoutService}'s checkout (UC-10, II.2.8) through its public methods, with its outbound ports
 * doubled: what a buyer is left with when checkout refuses before any charge, rolls back after one, or
 * completes. A plain unit suite, so it lives here rather than under {@code acceptance/}, whose classes boot
 * Spring (NaimElijah/Ticketeer#189); {@link CheckoutServiceTest} holds the service's other unit tests.
 */
class CheckoutServiceOutcomesTest {

    // Holds are stamped and judged at this instant, as UTC wall time from the injected clock (ADR-008).
    private static final Instant T0 = Instant.parse("2027-07-15T17:00:00Z");
    private static final LocalDateTime NOW = LocalDateTime.ofInstant(T0, ZoneOffset.UTC);

    private ActiveOrderRepository activeOrderRepository;
    private EventRepository eventRepository;
    private TicketRepository ticketRepository;
    private OrderReceiptRepository orderReceiptRepository;
    private TicketIssuer ticketIssuer;
    private PaymentGateway paymentGateway;
    private ApplicationEventPublisher eventPublisher;
    private SessionManager sessionManager;
    private UserRepository userRepository;

    private CheckoutService checkoutService;
    private MarketGate marketGate;

    private ProductionCompanyRepository companyRepository;

    private static final String VALID_TOKEN = "valid-token";
    private static final String REVOKED_TOKEN = "revoked-token";
    private static final String EXPIRED_TOKEN = "expired-token";
    private static final String IDEMPOTENCY_KEY = "idem-123";
    private static final String CURRENCY = "ILS";
    private static final CardDetailsDTO PAYMENT_METHOD_TOKEN =
            new CardDetailsDTO("4111111111111234", "123", 12, 2030, "Test Holder");

    private static final int USER_ID = 1;
    private static final int PAYMENT_TRANSACTION_ID = 123;
    private static final int EVENT_ID_1 = 10;
    private static final int EVENT_ID_2 = 20;
    private static final int ZONE_ID_1 = 100;
    private static final int ZONE_ID_2 = 200;

    private Event event1;
    private Event event2;

    @BeforeEach
    void setUp() {
        activeOrderRepository = mock(ActiveOrderRepository.class);
        eventRepository = mock(EventRepository.class);
        // A sale loads its events through findForSale; each test stubs the events once, on findById.
        when(eventRepository.findForSale(anyInt())).thenAnswer(call -> eventRepository.findById(call.<Integer>getArgument(0)));
        ticketRepository = mock(TicketRepository.class);
        AtomicInteger ticketIds = new AtomicInteger(1);
        when(ticketRepository.nextId()).thenAnswer(invocation -> ticketIds.getAndIncrement());
        userRepository = mock(UserRepository.class);
        companyRepository = CompanyRepositories.everyCompanyWithoutAPolicy();

        orderReceiptRepository = mock(OrderReceiptRepository.class);
        AtomicInteger receiptIds = new AtomicInteger(1);
        when(orderReceiptRepository.nextId()).thenAnswer(invocation -> receiptIds.getAndIncrement());

        ticketIssuer = mock(TicketIssuer.class);
        paymentGateway = mock(PaymentGateway.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        sessionManager = mock(SessionManager.class);

        marketGate = mock(MarketGate.class);
        when(marketGate.isOpen()).thenReturn(true);

        // Catalog owns inventory mutation behind InventoryCommandPort. Wire the real InventoryService
        // onto the same mock event store (+ mock company port) so the existing event/zone/seat stubs
        // drive confirm/validate/price/policy a layer down; CheckoutService keeps eventRepository for
        // the Phase-3 buyer-lock.
        checkoutService = new CheckoutService(
                activeOrderRepository,
                eventRepository,
                new InventoryService(eventRepository, companyRepository, TestTransactions.recordingManager()),
                ticketRepository,
                orderReceiptRepository,
                ticketIssuer,
                paymentGateway,
                eventPublisher,
                sessionManager,
                userRepository,
                marketGate,
                TestTransactions.recordingManager(),
                Clock.fixed(T0, ZoneOffset.UTC)
        );

        event1 = mock(Event.class);
        event2 = mock(Event.class);

        when(event1.getName()).thenReturn("Event 1");
        when(event2.getName()).thenReturn("Event 2");

        when(event1.calculatePrice(anyInt(), anyDouble(), any(LocalDateTime.class)))
                .thenAnswer(invocation -> ((Integer) invocation.getArgument(0)) * 10.0);

        when(event2.calculatePrice(anyInt(), anyDouble(), any(LocalDateTime.class)))
                .thenAnswer(invocation -> ((Integer) invocation.getArgument(0)) * 20.0);

        when(event1.calculatePriceforoneticket(anyInt(), anyDouble(), any(LocalDateTime.class)))
                .thenReturn(10.0);

        when(event2.calculatePriceforoneticket(anyInt(), anyDouble(), any(LocalDateTime.class)))
                .thenReturn(20.0);

        when(eventRepository.findById(EVENT_ID_1)).thenReturn(event1);
        when(eventRepository.findById(EVENT_ID_2)).thenReturn(event2);

        VenueMap venueMap1 = mock(VenueMap.class);
        InventoryZone mockZone1 = mock(InventoryZone.class);
        when(mockZone1.getReservedAmount()).thenReturn(Integer.MAX_VALUE);
        when(venueMap1.getZone(anyInt())).thenReturn(mockZone1);
        when(event1.getVenueMap()).thenReturn(venueMap1);
        when(event1.getStatus()).thenReturn(EventStatus.ON_SALE);

        VenueMap venueMap2 = mock(VenueMap.class);
        InventoryZone mockZone2 = mock(InventoryZone.class);
        when(mockZone2.getReservedAmount()).thenReturn(Integer.MAX_VALUE);
        when(venueMap2.getZone(anyInt())).thenReturn(mockZone2);
        when(event2.getVenueMap()).thenReturn(venueMap2);
        when(event2.getStatus()).thenReturn(EventStatus.ON_SALE);
    }

    private void validSession() {
        when(sessionManager.validateToken(VALID_TOKEN)).thenReturn(true);
        when(sessionManager.extractUserId(VALID_TOKEN)).thenReturn(USER_ID);
        User user = mock(User.class);
    when(user.getUserId()).thenReturn(USER_ID);
    when(user.getAge()).thenReturn(72);

    when(userRepository.getUserById(USER_ID)).thenReturn(user);
    }

    // The member's real cart, holding one standing place of a real ON_SALE event: a cart nothing but the
    // input under test can refuse. Returns the place's zone, of five places at 100.
    private StandingZone givenAMemberCartHoldingOnePlace() {
        StandingZone zone = new StandingZone(ZONE_ID_1, "Floor", 5, 100.0);
        when(eventRepository.findById(EVENT_ID_1)).thenReturn(aRealEvent(EVENT_ID_1, zone));
        holdOnePlace(givenTheMembersCart(), EVENT_ID_1, zone);
        return zone;
    }

    // The member's empty real cart, as the order repository returns it.
    private ActiveOrder givenTheMembersCart() {
        ActiveOrder cart = new ActiveOrder(USER_ID, NOW);
        when(activeOrderRepository.getByUserId(USER_ID)).thenReturn(cart);
        return cart;
    }

    // Puts one standing place of the zone in the cart, held in the zone under the cart's order key.
    private static void holdOnePlace(ActiveOrder cart, int eventId, StandingZone zone) {
        cart.addStandingReservation(eventId, zone.getId(), 1, zone.getprice(), NOW);
        zone.reserve(InventorySelection.standing(1, cart.getOrderKey()));
    }

    // An ON_SALE event of the given zones, ten days after NOW, with no purchase policy and no discount.
    private static Event aRealEvent(int eventId, InventoryZone... zones) {
        LocalDateTime showStart = NOW.plusDays(10);
        return new Event(eventId, "Concert", 4.5, List.of("Artist"), EventCategory.CONCERT, 100,
                EventStatus.ON_SALE, new VenueMap(1, new Location("Israel", "Tel Aviv"), List.of(zones)),
                List.of(new ShowDate(showStart, showStart.plusHours(2))), ZoneId.of("Asia/Jerusalem"),
                new NoPurchasePolicy(), new DiscountPolicy(0));
    }

    // checkoutMember wraps every refusal in its generic checkout failure, so the reason is the cause: a bare
    // check of the wrapper's message passes on any later failure too. The gateway was never asked.
    private void assertRefusedBeforeAnyCharge(RuntimeException failure, Class<? extends Throwable> reason,
            String message) {
        Throwable cause = failure.getCause();
        assertInstanceOf(reason, cause, "the refusal must carry its own reason, but the cause was " + cause);
        assertEquals(message, cause.getMessage(), "the refusal's reason");
        verify(paymentGateway, never()).charge(any());
    }

    // A refusal of the request itself comes before the cart is read, so the cart and its hold are untouched.
    private void assertTheCartStillHoldsItsPlace(StandingZone zone) {
        assertEquals(1, activeOrderRepository.getByUserId(USER_ID).getItems().size(),
                "a refused request must leave the cart's line");
        assertEquals(1, zone.getReservedAmount(), "a refused request must leave the place held");
    }

    private CartLineItem item(int eventId, int zoneId, double price) {
        CartLineItem item = mock(CartLineItem.class);
        when(item.geteventId()).thenReturn(eventId);
        when(item.getzoneId()).thenReturn(zoneId);
        when(item.getPriceAtReservation()).thenReturn(price);
        return item;
    }

    private ActiveOrder order(List<CartLineItem> items, boolean canCheckout) {
        ActiveOrder order = mock(ActiveOrder.class);
        when(order.getItems()).thenReturn(items);
        when(order.validateCanCheckout(any())).thenReturn(canCheckout);
        when(order.ReturnToStock()).thenReturn(items);
        when(order.isCheckoutInProgress()).thenReturn(true);
        when(order.getOrderKey()).thenReturn("acceptance-order-key");
        return order;
    }

    private PaymentResultDTO paymentResult() {
        return new PaymentResultDTO(
                123,
                "MockGateway",
                10.0,
                CURRENCY,
                NOW
        );
    }

    private IssuanceResultDTO issuanceResult(int count) {
        List<BarcodeDTO> barcodes = new java.util.ArrayList<>();

        for (int i = 1; i <= count; i++) {
            barcodes.add(new BarcodeDTO("barcode-" + i, "QR"));
        }

        return new IssuanceResultDTO(
                "iss-123",
                "MockIssuer",
                NOW,
                barcodes
        );
    }
    private AtomicBoolean trackRefund() {
    AtomicBoolean refundRequested = new AtomicBoolean(false);

    when(paymentGateway.refund(anyInt(), anyDouble()))
            .thenAnswer(invocation -> {
                refundRequested.set(true);

                int transactionId = invocation.getArgument(0);
                double amount = invocation.getArgument(1);

                return new RefundResultDTO(
                        "refund-" + transactionId,
                        String.valueOf(transactionId),
                        amount,
                        NOW,
                        List.of(),
                        List.of()
                );
            });

    return refundRequested;
}
    private void validPaymentAndIssuance(int ticketCount) {
        when(paymentGateway.charge(any(PaymentRequestDTO.class))).thenAnswer(invocation -> {
            PaymentRequestDTO request = invocation.getArgument(0);
            return new PaymentResultDTO(PAYMENT_TRANSACTION_ID, "MockGateway", request.amount(), CURRENCY, NOW);
        });
        when(ticketIssuer.issue(any(IssuanceRequestDTO.class))).thenReturn(issuanceResult(ticketCount));
    }


    private AtomicInteger trackReturnedTickets(Event event, int zoneId) {
        AtomicInteger returnedTickets = new AtomicInteger(0);

        doAnswer(invocation -> {
            InventorySelection selection = invocation.getArgument(1);
            returnedTickets.addAndGet(selection.getQuantity());
            return false;
        }).when(event).releaseInventory(eq(zoneId), any(InventorySelection.class));

        return returnedTickets;
    }

    @Test
    void GivenValidOrderWithOneTicket_WhenCheckout_ThenReturnCheckoutResult() {
        validSession();
        ActiveOrder order = order(List.of(item(EVENT_ID_1, ZONE_ID_1, 10.0)), true);
        when(activeOrderRepository.getByUserId(USER_ID)).thenReturn(order);
        validPaymentAndIssuance(1);

        CheckoutResultDTO result = checkoutService.checkoutMember(VALID_TOKEN, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN);

        assertEquals(10.0, result.totalCharged());
    }

    @Test
    void GivenValidOrderWithMultipleTicketsSameEventSameZone_WhenCheckout_ThenReturnCheckoutResult() {
        validSession();
        ActiveOrder order = order(List.of(
                item(EVENT_ID_1, ZONE_ID_1, 10.0),
                item(EVENT_ID_1, ZONE_ID_1, 10.0)
        ), true);

        when(activeOrderRepository.getByUserId(USER_ID)).thenReturn(order);
        validPaymentAndIssuance(2);

        CheckoutResultDTO result = checkoutService.checkoutMember(VALID_TOKEN, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN);

        assertEquals(20.0, result.totalCharged());
    }

    @Test
    void GivenValidOrderWithMultipleTicketsSameEventDifferentZones_WhenCheckout_ThenReturnCheckoutResult() {
        validSession();
        ActiveOrder order = order(List.of(
                item(EVENT_ID_1, ZONE_ID_1, 10.0),
                item(EVENT_ID_1, ZONE_ID_2, 10.0)
        ), true);

        when(activeOrderRepository.getByUserId(USER_ID)).thenReturn(order);
        validPaymentAndIssuance(2);

        CheckoutResultDTO result = checkoutService.checkoutMember(VALID_TOKEN, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN);

        assertEquals(20.0, result.totalCharged());
    }

    @Test
    void GivenValidOrderWithTicketsFromDifferentEvents_WhenCheckout_ThenReturnCheckoutResult() {
        validSession();
        ActiveOrder order = order(List.of(
                item(EVENT_ID_1, ZONE_ID_1, 10.0),
                item(EVENT_ID_2, ZONE_ID_1, 20.0)
        ), true);

        when(activeOrderRepository.getByUserId(USER_ID)).thenReturn(order);
        validPaymentAndIssuance(2);

        CheckoutResultDTO result = checkoutService.checkoutMember(VALID_TOKEN, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN);

        assertEquals(30.0, result.totalCharged());
    }

    @Test
    void GivenBlankToken_WhenCheckoutMember_ThenRefusedAsAMissingTokenAndNothingCharged() {
        StandingZone zone = givenAMemberCartHoldingOnePlace();

        RuntimeException failure = assertThrows(RuntimeException.class, () ->
                checkoutService.checkoutMember(" ", IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN));

        assertRefusedBeforeAnyCharge(failure, InvalidTokenException.class,
                "Invalid authentication token: Missing authentication token");
        assertTheCartStillHoldsItsPlace(zone);
    }

    // The session manager refuses a token by throwing, as JwtSessionManager does for a revoked session; it
    // answers false only for a null or blank token.
    @Test
    void GivenARevokedToken_WhenCheckoutMember_ThenRefusedAsAnInvalidTokenAndNothingCharged() {
        StandingZone zone = givenAMemberCartHoldingOnePlace();
        when(sessionManager.validateToken(REVOKED_TOKEN)).thenThrow(new InvalidTokenException("session not found"));

        RuntimeException failure = assertThrows(RuntimeException.class, () ->
                checkoutService.checkoutMember(REVOKED_TOKEN, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN));

        assertRefusedBeforeAnyCharge(failure, InvalidTokenException.class,
                "Invalid authentication token: session not found");
        assertTheCartStillHoldsItsPlace(zone);
    }

    @Test
    void GivenAnExpiredToken_WhenCheckoutMember_ThenRefusedAsAnExpiredSessionAndNothingCharged() {
        StandingZone zone = givenAMemberCartHoldingOnePlace();
        when(sessionManager.validateToken(EXPIRED_TOKEN)).thenThrow(new SessionExpiredException());

        RuntimeException failure = assertThrows(RuntimeException.class, () ->
                checkoutService.checkoutMember(EXPIRED_TOKEN, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN));

        assertRefusedBeforeAnyCharge(failure, SessionExpiredException.class, "Session expired; please re-authenticate");
        assertTheCartStillHoldsItsPlace(zone);
    }

    @Test
    void GivenMissingIdempotencyKey_WhenCheckoutMember_ThenRefusedAsAnIllegalArgumentAndNothingCharged() {
        validSession();
        StandingZone zone = givenAMemberCartHoldingOnePlace();

        RuntimeException failure = assertThrows(RuntimeException.class, () ->
                checkoutService.checkoutMember(VALID_TOKEN, null, CURRENCY, PAYMENT_METHOD_TOKEN));

        assertRefusedBeforeAnyCharge(failure, IllegalArgumentException.class, "Missing idempotency key");
        assertTheCartStillHoldsItsPlace(zone);
    }

    @Test
    void GivenBlankIdempotencyKey_WhenCheckoutMember_ThenRefusedAsAnIllegalArgumentAndNothingCharged() {
        validSession();
        StandingZone zone = givenAMemberCartHoldingOnePlace();

        RuntimeException failure = assertThrows(RuntimeException.class, () ->
                checkoutService.checkoutMember(VALID_TOKEN, " ", CURRENCY, PAYMENT_METHOD_TOKEN));

        assertRefusedBeforeAnyCharge(failure, IllegalArgumentException.class, "Missing idempotency key");
        assertTheCartStillHoldsItsPlace(zone);
    }

    @Test
    void GivenMissingCurrency_WhenCheckoutMember_ThenRefusedAsAnIllegalArgumentAndNothingCharged() {
        validSession();
        StandingZone zone = givenAMemberCartHoldingOnePlace();

        RuntimeException failure = assertThrows(RuntimeException.class, () ->
                checkoutService.checkoutMember(VALID_TOKEN, IDEMPOTENCY_KEY, null, PAYMENT_METHOD_TOKEN));

        assertRefusedBeforeAnyCharge(failure, IllegalArgumentException.class, "Missing currency");
        assertTheCartStillHoldsItsPlace(zone);
    }

    @Test
    void GivenBlankCurrency_WhenCheckoutMember_ThenRefusedAsAnIllegalArgumentAndNothingCharged() {
        validSession();
        StandingZone zone = givenAMemberCartHoldingOnePlace();

        RuntimeException failure = assertThrows(RuntimeException.class, () ->
                checkoutService.checkoutMember(VALID_TOKEN, IDEMPOTENCY_KEY, " ", PAYMENT_METHOD_TOKEN));

        assertRefusedBeforeAnyCharge(failure, IllegalArgumentException.class, "Missing currency");
        assertTheCartStillHoldsItsPlace(zone);
    }

    @Test
    void GivenMissingCard_WhenCheckoutMember_ThenRefusedAsAnIllegalArgumentAndNothingCharged() {
        validSession();
        StandingZone zone = givenAMemberCartHoldingOnePlace();

        RuntimeException failure = assertThrows(RuntimeException.class, () ->
                checkoutService.checkoutMember(VALID_TOKEN, IDEMPOTENCY_KEY, CURRENCY, null));

        assertRefusedBeforeAnyCharge(failure, IllegalArgumentException.class, "Missing card details");
        assertTheCartStillHoldsItsPlace(zone);
    }

    @Test
    void GivenBlankCardNumber_WhenCheckoutMember_ThenRefusedAsAnIllegalArgumentAndNothingCharged() {
        validSession();
        StandingZone zone = givenAMemberCartHoldingOnePlace();

        RuntimeException failure = assertThrows(RuntimeException.class, () ->
                checkoutService.checkoutMember(VALID_TOKEN, IDEMPOTENCY_KEY, CURRENCY,
                        new CardDetailsDTO(" ", "123", 12, 2030, "X")));

        assertRefusedBeforeAnyCharge(failure, IllegalArgumentException.class, "Missing card details");
        assertTheCartStillHoldsItsPlace(zone);
    }

    // An event deleted after its place was held: nothing is charged, and the rollback still clears the cart,
    // since a deleted event holds nothing to release.
    @Test
    void GivenTheCartsEventNoLongerExists_WhenCheckoutMember_ThenRefusedAsEventNotFoundAndNothingCharged() {
        validSession();
        givenAMemberCartHoldingOnePlace();
        when(eventRepository.findById(EVENT_ID_1)).thenThrow(new EventNotFoundException(EVENT_ID_1));

        RuntimeException failure = assertThrows(RuntimeException.class, () ->
                checkoutService.checkoutMember(VALID_TOKEN, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN));

        assertRefusedBeforeAnyCharge(failure, EventNotFoundException.class, "Event not found: " + EVENT_ID_1);
        assertTrue(activeOrderRepository.getByUserId(USER_ID).isEmpty(), "the rollback must clear the cart");
    }

    // One of the cart's two events was deleted: nothing is charged, and the other event's place goes back.
    @Test
    void GivenOneOfTheCartsEventsNoLongerExists_WhenCheckoutMember_ThenRefusedAsEventNotFoundAndNothingCharged() {
        validSession();
        StandingZone kept = givenAMemberCartHoldingOnePlace();
        StandingZone ofTheDeletedEvent = new StandingZone(ZONE_ID_2, "Balcony", 5, 100.0);
        holdOnePlace(activeOrderRepository.getByUserId(USER_ID), EVENT_ID_2, ofTheDeletedEvent);
        when(eventRepository.findById(EVENT_ID_2)).thenThrow(new EventNotFoundException(EVENT_ID_2));

        RuntimeException failure = assertThrows(RuntimeException.class, () ->
                checkoutService.checkoutMember(VALID_TOKEN, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN));

        assertRefusedBeforeAnyCharge(failure, EventNotFoundException.class, "Event not found: " + EVENT_ID_2);
        assertEquals(5, kept.getAvailableAmount(), "the existing event's place must be back in stock");
        assertEquals(0, kept.getReservedAmount());
    }

    @Test
    void GivenTicketIssuerReturnsLessBarcodesThanItems_WhenCheckout_ThenThrowExceptionAndRefundPaymentAndReturnTicketsToStock() {
        validSession();
        AtomicBoolean refundRequested = trackRefund();
        AtomicInteger returnedTickets = trackReturnedTickets(event1, ZONE_ID_1);

        ActiveOrder order = order(List.of(
                item(EVENT_ID_1, ZONE_ID_1, 10.0),
                item(EVENT_ID_1, ZONE_ID_1, 10.0)
        ), true);

        when(activeOrderRepository.getByUserId(USER_ID)).thenReturn(order);
        when(paymentGateway.charge(any(PaymentRequestDTO.class))).thenReturn(paymentResult());
        when(ticketIssuer.issue(any(IssuanceRequestDTO.class))).thenReturn(issuanceResult(1));

        RuntimeException exception = assertThrows(RuntimeException.class, () ->
                checkoutService.checkoutMember(VALID_TOKEN, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN)
        );

        assertEquals(true, refundRequested.get());
        assertEquals(2, returnedTickets.get());
        assertEquals("Checkout failed, tickets returned to stock", exception.getMessage());
    }

    // TicketIssuer refuses by throwing TicketIssuanceFailedException. The buyer was charged, so the charge is
    // refunded in full, nothing is saved, and the place goes back to stock (II.2.8, business rule 4).
    @Test
    void GivenTheIssuerRefusesAfterTheCharge_WhenCheckoutMember_ThenTheChargeIsRefundedAndThePlaceReturned() {
        validSession();
        StandingZone zone = givenAMemberCartHoldingOnePlace();
        validPaymentAndIssuance(1);
        when(ticketIssuer.issue(any(IssuanceRequestDTO.class)))
                .thenThrow(new TicketIssuanceFailedException("rejected by the issuer"));

        RuntimeException failure = assertThrows(RuntimeException.class, () ->
                checkoutService.checkoutMember(VALID_TOKEN, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN));

        assertInstanceOf(TicketIssuanceFailedException.class, failure.getCause(),
                "the checkout must fail on the issuer's refusal, but the cause was " + failure.getCause());
        verify(paymentGateway).refund(PAYMENT_TRANSACTION_ID, 100.0);
        verify(ticketRepository, never()).save(any());
        verify(orderReceiptRepository, never()).save(any());
        assertTrue(activeOrderRepository.getByUserId(USER_ID).isEmpty(), "the rollback must clear the cart");
        assertEquals(5, zone.getAvailableAmount(), "the held place must be back in stock");
        assertEquals(0, zone.getReservedAmount());
    }

    @Test
    void GivenTicketRepositoryFailsToSaveTicket_WhenCheckout_ThenThrowExceptionAndRefundPaymentAndReturnTicketsToStock() {
        validSession();
        AtomicBoolean refundRequested = trackRefund();
        AtomicInteger returnedTickets = trackReturnedTickets(event1, ZONE_ID_1);

        ActiveOrder order = order(List.of(item(EVENT_ID_1, ZONE_ID_1, 10.0)), true);
        when(activeOrderRepository.getByUserId(USER_ID)).thenReturn(order);
        validPaymentAndIssuance(1);
        doThrow(new RuntimeException("save ticket failed")).when(ticketRepository).save(any(Ticket.class));

        RuntimeException exception = assertThrows(RuntimeException.class, () ->
                checkoutService.checkoutMember(VALID_TOKEN, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN)
        );

        // Ticket save fails BEFORE confirmInventorySale (the sale is committed last), so the inventory
        // is still RESERVED and is cleanly returned to stock, and the payment is refunded.
        assertEquals(true, refundRequested.get());
        assertEquals(1, returnedTickets.get());
        assertEquals("Checkout failed, tickets returned to stock", exception.getMessage());
    }

    @Test
    void GivenReceiptRepositoryFailsToSaveReceipt_WhenCheckout_ThenThrowExceptionAndRefundPaymentAndReturnTicketsToStock() {
        validSession();
        AtomicBoolean refundRequested = trackRefund();
        AtomicInteger returnedTickets = trackReturnedTickets(event1, ZONE_ID_1);

        ActiveOrder order = order(List.of(item(EVENT_ID_1, ZONE_ID_1, 10.0)), true);
        when(activeOrderRepository.getByUserId(USER_ID)).thenReturn(order);
        validPaymentAndIssuance(1);
        doThrow(new RuntimeException("save receipt failed")).when(orderReceiptRepository).save(any(OrderReceipt.class));

        RuntimeException exception = assertThrows(RuntimeException.class, () ->
                checkoutService.checkoutMember(VALID_TOKEN, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN)
        );

        // Receipt save fails BEFORE confirmInventorySale (the sale is committed last), so the inventory
        // is still RESERVED and is cleanly returned to stock, and the payment is refunded.
        assertEquals(true, refundRequested.get());
        assertEquals(1, returnedTickets.get());
        assertEquals("Checkout failed, tickets returned to stock", exception.getMessage());
    }

    @Test
    void GivenCheckoutFailsBeforePayment_WhenCheckout_ThenRefundIsNotRequested() {
        validSession();
        AtomicBoolean refundRequested = trackRefund();

        ActiveOrder order = order(List.of(item(EVENT_ID_1, ZONE_ID_1, 10.0)), false);
        when(activeOrderRepository.getByUserId(USER_ID)).thenReturn(order);

        assertThrows(RuntimeException.class, () ->
                checkoutService.checkoutMember(VALID_TOKEN, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN)
        );

        assertEquals(false, refundRequested.get());
    }

    @Test
    void GivenCheckoutSucceeds_WhenCheckout_ThenOrderIsMarkedAsBought() {
        validSession();
        AtomicBoolean orderBought = new AtomicBoolean(false);

        ActiveOrder order = order(List.of(item(EVENT_ID_1, ZONE_ID_1, 10.0)), true);

        doAnswer(invocation -> {
            orderBought.set(true);
            return null;
        }).when(order).buy();

        when(activeOrderRepository.getByUserId(USER_ID)).thenReturn(order);
        validPaymentAndIssuance(1);

        checkoutService.checkoutMember(VALID_TOKEN, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN);

        assertEquals(true, orderBought.get());
    }

    @Test
    void GivenCheckoutSucceeds_WhenCheckout_ThenTicketsAreSaved() {
        validSession();
        AtomicBoolean ticketSaved = new AtomicBoolean(false);

        doAnswer(invocation -> {
            ticketSaved.set(true);
            return null;
        }).when(ticketRepository).save(any(Ticket.class));

        ActiveOrder order = order(List.of(item(EVENT_ID_1, ZONE_ID_1, 10.0)), true);
        when(activeOrderRepository.getByUserId(USER_ID)).thenReturn(order);
        validPaymentAndIssuance(1);

        checkoutService.checkoutMember(VALID_TOKEN, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN);

        assertEquals(true, ticketSaved.get());
    }

    @Test
    void GivenCheckoutSucceeds_WhenCheckout_ThenReceiptIsSaved() {
        validSession();
        AtomicBoolean receiptSaved = new AtomicBoolean(false);

        doAnswer(invocation -> {
            receiptSaved.set(true);
            return null;
        }).when(orderReceiptRepository).save(any(OrderReceipt.class));

        ActiveOrder order = order(List.of(item(EVENT_ID_1, ZONE_ID_1, 10.0)), true);
        when(activeOrderRepository.getByUserId(USER_ID)).thenReturn(order);
        validPaymentAndIssuance(1);

        checkoutService.checkoutMember(VALID_TOKEN, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN);

        assertEquals(true, receiptSaved.get());
    }

    @Test
    void GivenCheckoutSucceeds_WhenCheckout_ThenUserIsNotified() {
        validSession();
        AtomicBoolean userNotified = new AtomicBoolean(false);

        doAnswer(invocation -> {
            userNotified.set(true);
            return null;
        }).when(eventPublisher).publishEvent(any(PurchaseCompletedNotice.class));

        ActiveOrder order = order(List.of(item(EVENT_ID_1, ZONE_ID_1, 10.0)), true);
        when(activeOrderRepository.getByUserId(USER_ID)).thenReturn(order);
        validPaymentAndIssuance(1);

        checkoutService.checkoutMember(VALID_TOKEN, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN);

        assertEquals(true, userNotified.get());
    }





    // Regression tests for the "stuck order" bug: after a successful checkout the consumed cart must
    // be deleted (not saved while still CHECKOUT_IN_PROGRESS), otherwise the buyer's next reservation
    // hits ensureModifiable() and throws "Cannot modify active order while checkout is in progress".
    // These use a REAL MemoryActiveOrderRepository so delete-vs-save is observable across lookups —
    // the mock-based suites cannot catch this (their save/delete are no-ops).

    private CheckoutService checkoutServiceWith(ActiveOrderRepository realRepo) {
        return new CheckoutService(
                realRepo,
                eventRepository,
                new InventoryService(eventRepository, companyRepository, TestTransactions.recordingManager()),
                ticketRepository,
                orderReceiptRepository,
                ticketIssuer,
                paymentGateway,
                eventPublisher,
                sessionManager,
                userRepository,
                marketGate,
                TestTransactions.recordingManager(),
                Clock.fixed(T0, ZoneOffset.UTC)
        );
    }

    @Test
    void GivenSuccessfulMemberCheckout_WhenBuyerChecksOutAgain_ThenSecondCheckoutSucceeds() {
        validSession();
        validPaymentAndIssuance(1);

        MemoryActiveOrderRepository realRepo = new MemoryActiveOrderRepository();
        CheckoutService service = checkoutServiceWith(realRepo);

        ActiveOrder firstOrder = new ActiveOrder(USER_ID, NOW);
        firstOrder.addStandingReservation(EVENT_ID_1, ZONE_ID_1, 1, 10.0, NOW);
        realRepo.save(firstOrder);

        service.checkoutMember(VALID_TOKEN, IDEMPOTENCY_KEY + "-1", CURRENCY, PAYMENT_METHOD_TOKEN);

        // The consumed cart must be gone so the buyer's next reservation/checkout starts clean.
        assertNull(realRepo.getByUserId(USER_ID));

        // Buyer comes back and reserves again on the same account, then checks out a second time.
        ActiveOrder secondOrder = new ActiveOrder(USER_ID, NOW);
        secondOrder.addStandingReservation(EVENT_ID_1, ZONE_ID_1, 1, 10.0, NOW);
        realRepo.save(secondOrder);

        CheckoutResultDTO secondResult = assertDoesNotThrow(() ->
                service.checkoutMember(VALID_TOKEN, IDEMPOTENCY_KEY + "-2", CURRENCY, PAYMENT_METHOD_TOKEN));

        assertEquals(10.0, secondResult.totalCharged());
    }

    @Test
    void GivenSuccessfulGuestCheckout_WhenGuestChecksOutAgain_ThenSecondCheckoutSucceeds() {
        String guestSessionId = "guest-sess";
        String guestEmail = "guest@test.com";
        when(sessionManager.validateCredential(guestSessionId)).thenReturn(true);
        when(sessionManager.validateGuestSession(guestSessionId)).thenReturn(true);
        validPaymentAndIssuance(1);

        MemoryActiveOrderRepository realRepo = new MemoryActiveOrderRepository();
        CheckoutService service = checkoutServiceWith(realRepo);

        ActiveOrder firstOrder = ActiveOrder.forGuest(guestSessionId, NOW);
        firstOrder.addStandingReservation(EVENT_ID_1, ZONE_ID_1, 1, 10.0, NOW);
        realRepo.save(firstOrder);

        service.checkoutGuest(guestSessionId, guestEmail, IDEMPOTENCY_KEY + "-g1", CURRENCY, PAYMENT_METHOD_TOKEN, 30);

        assertTrue(realRepo.getBySessionId(guestSessionId).isEmpty());

        ActiveOrder secondOrder = ActiveOrder.forGuest(guestSessionId, NOW);
        secondOrder.addStandingReservation(EVENT_ID_1, ZONE_ID_1, 1, 10.0, NOW);
        realRepo.save(secondOrder);

        assertDoesNotThrow(() ->
                service.checkoutGuest(guestSessionId, guestEmail, IDEMPOTENCY_KEY + "-g2", CURRENCY, PAYMENT_METHOD_TOKEN, 30));
    }

    @Test
    void GivenCheckoutSucceeds_WhenCheckout_ThenConsumedOrderIsDeleted() {
        validSession();
        ActiveOrder order = order(List.of(item(EVENT_ID_1, ZONE_ID_1, 10.0)), true);
        when(activeOrderRepository.getByUserId(USER_ID)).thenReturn(order);
        validPaymentAndIssuance(1);

        checkoutService.checkoutMember(VALID_TOKEN, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN);

        verify(activeOrderRepository).delete(order);
    }

    @Test
    @Disabled("Enable after seated reservation flow is wired end-to-end")
    void GivenReservedSeatedTickets_WhenCheckoutSucceeds_ThenTicketsHaveSeatNumbersAndSeatsBecomeSold() {
        
    }

    @Test
    @Disabled("Enable after checkout rollback supports seated zones")
    void GivenSeatedCheckoutPaymentSucceedsButIssuanceFails_WhenCheckout_ThenSeatsReturnToAvailable() {
        
    }
}
