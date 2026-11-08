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
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
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
        // onto the same mock event store (+ mock company port), so the real events each test stubs on
        // findById drive confirm/validate/price/policy a layer down; CheckoutService keeps eventRepository
        // for the Phase-3 buyer-lock.
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
    }

    private void validSession() {
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

    // The gateway charges what it is asked, and the issuer answers one barcode per ticket.
    private void validPaymentAndIssuance(int ticketCount) {
        when(paymentGateway.charge(any(PaymentRequestDTO.class))).thenAnswer(invocation -> {
            PaymentRequestDTO request = invocation.getArgument(0);
            return new PaymentResultDTO(PAYMENT_TRANSACTION_ID, "MockGateway", request.amount(), CURRENCY, NOW);
        });
        when(ticketIssuer.issue(any(IssuanceRequestDTO.class))).thenReturn(issuanceResult(ticketCount));
    }

    // Two places of one standing zone are charged and sold at the zone's own price; the result names the
    // receipt, the charge and each ticket.
    @Test
    void GivenTwoPlacesOfOneStandingZone_WhenCheckoutMember_ThenBothAreSoldAndChargedAtTheZonePrice() {
        validSession();
        StandingZone zone = givenAMemberCartHoldingOnePlace();
        holdOnePlace(activeOrderRepository.getByUserId(USER_ID), EVENT_ID_1, zone);
        validPaymentAndIssuance(2);

        CheckoutResultDTO result = checkoutService.checkoutMember(
                VALID_TOKEN, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN);

        assertEquals(new CheckoutResultDTO(200.0, 1, PAYMENT_TRANSACTION_ID, List.of(
                new CheckoutResultDTO.IssuedTicketDTO(1, "barcode-1"),
                new CheckoutResultDTO.IssuedTicketDTO(2, "barcode-2"))), result);
        ArgumentCaptor<PaymentRequestDTO> charge = ArgumentCaptor.forClass(PaymentRequestDTO.class);
        verify(paymentGateway).charge(charge.capture());
        assertEquals(200.0, charge.getValue().amount(), "two places at 100 each");
        assertEquals(2, zone.getSoldAmount(), "both places must be sold");
        assertEquals(0, zone.getReservedAmount(), "no place may stay held");
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
        when(sessionManager.extractUserId(REVOKED_TOKEN)).thenThrow(new InvalidTokenException("session not found"));

        RuntimeException failure = assertThrows(RuntimeException.class, () ->
                checkoutService.checkoutMember(REVOKED_TOKEN, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN));

        assertRefusedBeforeAnyCharge(failure, InvalidTokenException.class,
                "Invalid authentication token: session not found");
        assertTheCartStillHoldsItsPlace(zone);
    }

    @Test
    void GivenAnExpiredToken_WhenCheckoutMember_ThenRefusedAsAnExpiredSessionAndNothingCharged() {
        StandingZone zone = givenAMemberCartHoldingOnePlace();
        when(sessionManager.extractUserId(EXPIRED_TOKEN)).thenThrow(new SessionExpiredException());

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

    // A save fails before the sale is confirmed (the confirm runs last), so the place is still held: it goes back
    // to stock, and the charge is refunded.
    @Test
    void GivenTheTicketSaveFails_WhenCheckoutMember_ThenTheChargeIsRefundedAndThePlaceReturned() {
        validSession();
        StandingZone zone = givenAMemberCartHoldingOnePlace();
        validPaymentAndIssuance(1);
        RuntimeException saveFailure = new DataIntegrityViolationException("could not insert the ticket");
        doThrow(saveFailure).when(ticketRepository).save(any(Ticket.class));

        RuntimeException failure = assertThrows(RuntimeException.class, () ->
                checkoutService.checkoutMember(VALID_TOKEN, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN));

        assertSame(saveFailure, failure.getCause(), "the checkout must fail on the failed save");
        verify(orderReceiptRepository, never()).save(any());
        assertTheChargeRefundedAndThePlaceReturned(zone);
    }

    @Test
    void GivenTheReceiptSaveFails_WhenCheckoutMember_ThenTheChargeIsRefundedAndThePlaceReturned() {
        validSession();
        StandingZone zone = givenAMemberCartHoldingOnePlace();
        validPaymentAndIssuance(1);
        RuntimeException saveFailure = new DataIntegrityViolationException("could not insert the receipt");
        doThrow(saveFailure).when(orderReceiptRepository).save(any(OrderReceipt.class));

        RuntimeException failure = assertThrows(RuntimeException.class, () ->
                checkoutService.checkoutMember(VALID_TOKEN, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN));

        assertSame(saveFailure, failure.getCause(), "the checkout must fail on the failed save");
        assertTheChargeRefundedAndThePlaceReturned(zone);
    }

    // The whole charge of one place at 100 is refunded, nothing is sold, and the place is back in stock.
    private void assertTheChargeRefundedAndThePlaceReturned(StandingZone zone) {
        verify(paymentGateway).refund(PAYMENT_TRANSACTION_ID, 100.0);
        assertEquals(0, zone.getSoldAmount(), "nothing may be sold");
        assertEquals(5, zone.getAvailableAmount(), "the held place must be back in stock");
        assertTrue(activeOrderRepository.getByUserId(USER_ID).isEmpty(), "the rollback must clear the cart");
    }

    // The member hears of the purchase through a notice naming the member, the total and the tickets, which
    // the notifications context delivers after commit (ADR-004).
    @Test
    void GivenCheckoutSucceeds_WhenCheckoutMember_ThenAPurchaseCompletedNoticeNamesTheMemberTotalAndTickets() {
        validSession();
        StandingZone zone = givenAMemberCartHoldingOnePlace();
        holdOnePlace(activeOrderRepository.getByUserId(USER_ID), EVENT_ID_1, zone);
        validPaymentAndIssuance(2);

        checkoutService.checkoutMember(VALID_TOKEN, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN);

        ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(published.capture());
        assertEquals(new PurchaseCompletedNotice(USER_ID, 200.0, List.of(1, 2)), published.getValue());
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
        StandingZone zone = new StandingZone(ZONE_ID_1, "Floor", 5, 100.0);
        when(eventRepository.findById(EVENT_ID_1)).thenReturn(aRealEvent(EVENT_ID_1, zone));

        MemoryActiveOrderRepository realRepo = new MemoryActiveOrderRepository();
        CheckoutService service = checkoutServiceWith(realRepo);

        ActiveOrder firstOrder = new ActiveOrder(USER_ID, NOW);
        holdOnePlace(firstOrder, EVENT_ID_1, zone);
        realRepo.save(firstOrder);

        service.checkoutMember(VALID_TOKEN, IDEMPOTENCY_KEY + "-1", CURRENCY, PAYMENT_METHOD_TOKEN);

        // The consumed cart must be gone so the buyer's next reservation/checkout starts clean.
        assertNull(realRepo.getByUserId(USER_ID));

        // Buyer comes back and reserves again on the same account, then checks out a second time.
        ActiveOrder secondOrder = new ActiveOrder(USER_ID, NOW);
        holdOnePlace(secondOrder, EVENT_ID_1, zone);
        realRepo.save(secondOrder);

        CheckoutResultDTO secondResult =
                service.checkoutMember(VALID_TOKEN, IDEMPOTENCY_KEY + "-2", CURRENCY, PAYMENT_METHOD_TOKEN);

        assertEquals(100.0, secondResult.totalCharged());
        assertEquals(2, zone.getSoldAmount(), "each checkout must sell its place");
    }

    @Test
    void GivenSuccessfulGuestCheckout_WhenGuestChecksOutAgain_ThenSecondCheckoutSucceeds() {
        String guestSessionId = "guest-sess";
        String guestEmail = "guest@test.com";
        when(sessionManager.validateGuestSession(guestSessionId)).thenReturn(true);
        validPaymentAndIssuance(1);
        StandingZone zone = new StandingZone(ZONE_ID_1, "Floor", 5, 100.0);
        when(eventRepository.findById(EVENT_ID_1)).thenReturn(aRealEvent(EVENT_ID_1, zone));

        MemoryActiveOrderRepository realRepo = new MemoryActiveOrderRepository();
        CheckoutService service = checkoutServiceWith(realRepo);

        ActiveOrder firstOrder = ActiveOrder.forGuest(guestSessionId, NOW);
        holdOnePlace(firstOrder, EVENT_ID_1, zone);
        realRepo.save(firstOrder);

        service.checkoutGuest(guestSessionId, guestEmail, IDEMPOTENCY_KEY + "-g1", CURRENCY, PAYMENT_METHOD_TOKEN, 30);

        assertTrue(realRepo.getBySessionId(guestSessionId).isEmpty());

        ActiveOrder secondOrder = ActiveOrder.forGuest(guestSessionId, NOW);
        holdOnePlace(secondOrder, EVENT_ID_1, zone);
        realRepo.save(secondOrder);

        CheckoutResultDTO secondResult = service.checkoutGuest(
                guestSessionId, guestEmail, IDEMPOTENCY_KEY + "-g2", CURRENCY, PAYMENT_METHOD_TOKEN, 30);

        assertEquals(100.0, secondResult.totalCharged());
        assertEquals(2, zone.getSoldAmount(), "each checkout must sell its place");
    }

    // A bought cart is consumed: buy() empties it and the repository deletes it.
    @Test
    void GivenCheckoutSucceeds_WhenCheckoutMember_ThenTheConsumedCartIsEmptiedAndDeleted() {
        validSession();
        givenAMemberCartHoldingOnePlace();
        ActiveOrder cart = activeOrderRepository.getByUserId(USER_ID);
        validPaymentAndIssuance(1);

        checkoutService.checkoutMember(VALID_TOKEN, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN);

        assertTrue(cart.isEmpty(), "the bought lines must leave the cart");
        verify(activeOrderRepository).delete(cart);
    }
}
