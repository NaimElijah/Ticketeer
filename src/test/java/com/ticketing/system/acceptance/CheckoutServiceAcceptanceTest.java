package com.ticketing.system.acceptance;
import com.ticketing.system.identity.application.port.out.SessionManager;

import com.ticketing.system.Core.Application.dto.*;
import com.ticketing.system.Core.Application.interfaces.*;
import com.ticketing.system.identity.application.service.AuthenticationService;
import com.ticketing.system.Core.Application.services.CheckoutService;
import com.ticketing.system.Core.Application.services.CompanyManagementService;
import com.ticketing.system.Core.Application.services.EventManagementService;
import com.ticketing.system.Core.Application.services.SystemAdminService;
import com.ticketing.system.Core.Domain.ActiveOrder.*;
import com.ticketing.system.Core.Domain.Tickets.*;
import com.ticketing.system.Core.Domain.company.IProductionCompanyRepository;
import com.ticketing.system.Core.Domain.events.*;
import com.ticketing.system.Core.Domain.orders.*;
import com.ticketing.system.identity.application.port.out.UserRepository;
import com.ticketing.system.identity.domain.User;
import com.ticketing.system.Infrastructure.persistence.ActiveOrderPersistence.MemoryActiveOrderRepository;
import com.ticketing.system.testutil.TestTransactions;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class CheckoutServiceAcceptanceTest {

    private IActiveOrderRepository activeOrderRepository;
    private IEventRepository eventRepository;
    private ITicketRepository ticketRepository;
    private IOrderReceiptRepository orderReceiptRepository;
    private ITicketIssuer ticketIssuer;
    private IPaymentGateway paymentGateway;
    private INotificationService notificationService;
    private SessionManager sessionManager;
    private UserRepository userRepository;

    private CheckoutService checkoutService;
    private SystemAdminService systemAdminService;

    @Autowired private AuthenticationService authService;
    @Autowired private CompanyManagementService companyService;
    @Autowired private EventManagementService eventManagementService;
    private IProductionCompanyRepository companyRepository;
    @Autowired private IEventRepository eventRepository1;
    @Autowired private ITicketRepository ticketRepository1;
    @Autowired private IOrderReceiptRepository orderReceiptRepository1;


    private AuthTokenDTO registerAndLoginMember(String name) {
    String sid = authService.startGuestSession().sessionId();

    authService.register(new RegisterRequestDTO(
            name,
            name + "@test.com",
            "Password1",
            sid,
            72
    ));

    return authService
            .login(new LoginRequestDTO(name, "Password1", sid))
            .authToken();
    }

    private static final String VALID_TOKEN = "valid-token";
    private static final String INVALID_TOKEN = "invalid-token";
    private static final String IDEMPOTENCY_KEY = "idem-123";
    private static final String CURRENCY = "ILS";
    private static final CardDetailsDTO PAYMENT_METHOD_TOKEN =
            new CardDetailsDTO("4111111111111234", "123", 12, 2030, "Test Holder");

    private static final int USER_ID = 1;
    private static final int EVENT_ID_1 = 10;
    private static final int EVENT_ID_2 = 20;
    private static final int ZONE_ID_1 = 100;
    private static final int ZONE_ID_2 = 200;

    private Event event1;
    private Event event2;

    @BeforeEach
    void setUp() {
        activeOrderRepository = mock(IActiveOrderRepository.class);
        eventRepository = mock(IEventRepository.class);
        ticketRepository = mock(ITicketRepository.class);
        userRepository = mock(UserRepository.class);
        companyRepository = mock(IProductionCompanyRepository.class);

        orderReceiptRepository = mock(IOrderReceiptRepository.class);
        AtomicInteger receiptIds = new AtomicInteger(1);
        when(orderReceiptRepository.nextId()).thenAnswer(invocation -> receiptIds.getAndIncrement());

        ticketIssuer = mock(ITicketIssuer.class);
        paymentGateway = mock(IPaymentGateway.class);
        notificationService = mock(INotificationService.class);
        sessionManager = mock(SessionManager.class);

        systemAdminService = mock(SystemAdminService.class);
        when(systemAdminService.isMarketOpen()).thenReturn(true);

        checkoutService = new CheckoutService(
                activeOrderRepository,
                eventRepository,
                ticketRepository,
                orderReceiptRepository,
                ticketIssuer,
                paymentGateway,
                notificationService,
                sessionManager,
                userRepository,
                companyRepository,
                systemAdminService,
                TestTransactions.noOpManager()
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
        when(order.validateCanCheckout()).thenReturn(canCheckout);
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
                LocalDateTime.now()
        );
    }

    private IssuanceResultDTO issuanceResult(int count) {
        List<BarcodeDTO> barcodes = new java.util.ArrayList<>();

        for (int i = 1; i <= count; i++) {
            barcodes.add(new BarcodeDTO(i, "barcode-" + i, "QR"));
        }

        return new IssuanceResultDTO(
                "iss-123",
                "MockIssuer",
                LocalDateTime.now(),
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
                        LocalDateTime.now(),
                        List.of(),
                        List.of()
                );
            });

    return refundRequested;
}
    private void validPaymentAndIssuance(int ticketCount) {
        when(paymentGateway.charge(any(PaymentRequestDTO.class))).thenAnswer(invocation -> {
            PaymentRequestDTO request = invocation.getArgument(0);
            return new PaymentResultDTO(123, "MockGateway", request.amount(), CURRENCY, LocalDateTime.now());
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
    void GivenNullToken_WhenCheckout_ThenThrowException() {
        RuntimeException exception = assertThrows(RuntimeException.class, () ->
                checkoutService.checkoutMember(null, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN)
        );

        assertEquals("Checkout failed, tickets returned to stock", exception.getMessage());
    }

    @Test
    void GivenBlankToken_WhenCheckout_ThenThrowException() {
        RuntimeException exception = assertThrows(RuntimeException.class, () ->
                checkoutService.checkoutMember(" ", IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN)
        );

        assertEquals("Checkout failed, tickets returned to stock", exception.getMessage());
    }

    @Test
    void GivenInvalidToken_WhenCheckout_ThenThrowException() {
        when(sessionManager.validateToken(INVALID_TOKEN)).thenReturn(false);

        RuntimeException exception = assertThrows(RuntimeException.class, () ->
                checkoutService.checkoutMember(INVALID_TOKEN, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN)
        );

        assertEquals("Checkout failed, tickets returned to stock", exception.getMessage());
    }

    @Test
    void GivenExpiredToken_WhenCheckout_ThenThrowException() {
        when(sessionManager.validateToken(VALID_TOKEN)).thenReturn(false);

        RuntimeException exception = assertThrows(RuntimeException.class, () ->
                checkoutService.checkoutMember(VALID_TOKEN, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN)
        );

        assertEquals("Checkout failed, tickets returned to stock", exception.getMessage());
    }

    @Test
    void GivenMissingIdempotencyKey_WhenCheckout_ThenThrowException() {
        validSession();

        RuntimeException exception = assertThrows(RuntimeException.class, () ->
                checkoutService.checkoutMember(VALID_TOKEN, null, CURRENCY, PAYMENT_METHOD_TOKEN)
        );

        assertEquals("Checkout failed, tickets returned to stock", exception.getMessage());
    }

    @Test
    void GivenBlankIdempotencyKey_WhenCheckout_ThenThrowException() {
        validSession();

        RuntimeException exception = assertThrows(RuntimeException.class, () ->
                checkoutService.checkoutMember(VALID_TOKEN, " ", CURRENCY, PAYMENT_METHOD_TOKEN)
        );

        assertEquals("Checkout failed, tickets returned to stock", exception.getMessage());
    }

    @Test
    void GivenMissingCurrency_WhenCheckout_ThenThrowException() {
        validSession();

        RuntimeException exception = assertThrows(RuntimeException.class, () ->
                checkoutService.checkoutMember(VALID_TOKEN, IDEMPOTENCY_KEY, null, PAYMENT_METHOD_TOKEN)
        );

        assertEquals("Checkout failed, tickets returned to stock", exception.getMessage());
    }

    @Test
    void GivenBlankCurrency_WhenCheckout_ThenThrowException() {
        validSession();

        RuntimeException exception = assertThrows(RuntimeException.class, () ->
                checkoutService.checkoutMember(VALID_TOKEN, IDEMPOTENCY_KEY, " ", PAYMENT_METHOD_TOKEN)
        );

        assertEquals("Checkout failed, tickets returned to stock", exception.getMessage());
    }

    @Test
    void GivenMissingPaymentMethodToken_WhenCheckout_ThenThrowException() {
        validSession();

        RuntimeException exception = assertThrows(RuntimeException.class, () ->
                checkoutService.checkoutMember(VALID_TOKEN, IDEMPOTENCY_KEY, CURRENCY, null)
        );

        assertEquals("Checkout failed, tickets returned to stock", exception.getMessage());
    }

    @Test
    void GivenBlankCard_WhenCheckout_ThenThrowException() {
        validSession();

        RuntimeException exception = assertThrows(RuntimeException.class, () ->
                checkoutService.checkoutMember(VALID_TOKEN, IDEMPOTENCY_KEY, CURRENCY,
                        new CardDetailsDTO(" ", "123", 12, 2030, "X"))
        );

        assertEquals("Checkout failed, tickets returned to stock", exception.getMessage());
    }

    @Test
    void GivenUserHasNoActiveOrder_WhenCheckout_ThenThrowException() {
        validSession();
        when(activeOrderRepository.getByUserId(USER_ID)).thenReturn(null);

        RuntimeException exception = assertThrows(RuntimeException.class, () ->
                checkoutService.checkoutMember(VALID_TOKEN, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN)
        );

        assertEquals("Checkout failed, tickets returned to stock", exception.getMessage());
    }

    @Test
    void GivenActiveOrderCannotCheckout_WhenCheckout_ThenThrowException() {
        validSession();
        ActiveOrder order = order(List.of(item(EVENT_ID_1, ZONE_ID_1, 10.0)), false);
        when(activeOrderRepository.getByUserId(USER_ID)).thenReturn(order);

        RuntimeException exception = assertThrows(RuntimeException.class, () ->
                checkoutService.checkoutMember(VALID_TOKEN, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN)
        );

        assertEquals("Checkout failed, tickets returned to stock", exception.getMessage());
    }

    @Test
    void GivenActiveOrderIsExpired_WhenCheckout_ThenThrowException() {
        validSession();
        ActiveOrder order = order(List.of(item(EVENT_ID_1, ZONE_ID_1, 10.0)), false);
        when(activeOrderRepository.getByUserId(USER_ID)).thenReturn(order);

        RuntimeException exception = assertThrows(RuntimeException.class, () ->
                checkoutService.checkoutMember(VALID_TOKEN, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN)
        );

        assertEquals("Checkout failed, tickets returned to stock", exception.getMessage());
    }

    @Test
    void GivenActiveOrderIsEmpty_WhenCheckout_ThenThrowException() {
        validSession();
        ActiveOrder order = order(List.of(), false);
        when(activeOrderRepository.getByUserId(USER_ID)).thenReturn(order);

        RuntimeException exception = assertThrows(RuntimeException.class, () ->
                checkoutService.checkoutMember(VALID_TOKEN, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN)
        );

        assertEquals("Checkout failed, tickets returned to stock", exception.getMessage());
    }

    @Test
    void GivenEventDoesNotExist_WhenCheckout_ThenThrowException() {
        validSession();
        ActiveOrder order = order(List.of(item(EVENT_ID_1, ZONE_ID_1, 10.0)), true);
        when(activeOrderRepository.getByUserId(USER_ID)).thenReturn(order);
        when(eventRepository.findById(EVENT_ID_1)).thenReturn(null);

        RuntimeException exception = assertThrows(RuntimeException.class, () ->
                checkoutService.checkoutMember(VALID_TOKEN, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN)
        );

        assertEquals("Checkout failed, tickets returned to stock", exception.getMessage());
    }

    @Test
    void GivenOneEventFromOrderDoesNotExist_WhenCheckout_ThenThrowException() {
        validSession();
        ActiveOrder order = order(List.of(
                item(EVENT_ID_1, ZONE_ID_1, 10.0),
                item(EVENT_ID_2, ZONE_ID_1, 20.0)
        ), true);

        when(activeOrderRepository.getByUserId(USER_ID)).thenReturn(order);
        when(eventRepository.findById(EVENT_ID_2)).thenReturn(null);

        RuntimeException exception = assertThrows(RuntimeException.class, () ->
                checkoutService.checkoutMember(VALID_TOKEN, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN)
        );

        assertEquals("Checkout failed, tickets returned to stock", exception.getMessage());
    }

    @Test
    void GivenPaymentGatewayFails_WhenCheckout_ThenThrowExceptionAndReturnTicketsToStock() {
        validSession();
        AtomicInteger returnedTickets = trackReturnedTickets(event1, ZONE_ID_1);

        ActiveOrder order = order(List.of(item(EVENT_ID_1, ZONE_ID_1, 10.0)), true);
        when(activeOrderRepository.getByUserId(USER_ID)).thenReturn(order);
        when(paymentGateway.charge(any(PaymentRequestDTO.class))).thenReturn(null);

        RuntimeException exception = assertThrows(RuntimeException.class, () ->
                checkoutService.checkoutMember(VALID_TOKEN, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN)
        );

        assertEquals(1, returnedTickets.get());
        assertEquals("Checkout failed, tickets returned to stock", exception.getMessage());
    }

    @Test
    void GivenPaymentGatewayThrowsException_WhenCheckout_ThenThrowExceptionAndReturnTicketsToStock() {
        validSession();
        AtomicInteger returnedTickets = trackReturnedTickets(event1, ZONE_ID_1);

        ActiveOrder order = order(List.of(item(EVENT_ID_1, ZONE_ID_1, 10.0)), true);
        when(activeOrderRepository.getByUserId(USER_ID)).thenReturn(order);
        when(paymentGateway.charge(any(PaymentRequestDTO.class))).thenThrow(new RuntimeException("payment failed"));

        RuntimeException exception = assertThrows(RuntimeException.class, () ->
                checkoutService.checkoutMember(VALID_TOKEN, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN)
        );

        assertEquals(1, returnedTickets.get());
        assertEquals("Checkout failed, tickets returned to stock", exception.getMessage());
    }

    @Test
    void GivenTicketIssuerReturnsNull_WhenCheckout_ThenThrowExceptionAndRefundPaymentAndReturnTicketsToStock() {
        validSession();
        AtomicBoolean refundRequested = trackRefund();
        AtomicInteger returnedTickets = trackReturnedTickets(event1, ZONE_ID_1);

        ActiveOrder order = order(List.of(item(EVENT_ID_1, ZONE_ID_1, 10.0)), true);
        when(activeOrderRepository.getByUserId(USER_ID)).thenReturn(order);
        when(paymentGateway.charge(any(PaymentRequestDTO.class))).thenReturn(paymentResult());
        when(ticketIssuer.issue(any(IssuanceRequestDTO.class))).thenReturn(null);

        RuntimeException exception = assertThrows(RuntimeException.class, () ->
                checkoutService.checkoutMember(VALID_TOKEN, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN)
        );

        assertEquals(true, refundRequested.get());
        assertEquals(1, returnedTickets.get());
        assertEquals("Checkout failed, tickets returned to stock", exception.getMessage());
    }

    @Test
    void GivenTicketIssuerReturnsEmptyBarcodes_WhenCheckout_ThenThrowExceptionAndRefundPaymentAndReturnTicketsToStock() {
        validSession();
        AtomicBoolean refundRequested = trackRefund();
        AtomicInteger returnedTickets = trackReturnedTickets(event1, ZONE_ID_1);

        ActiveOrder order = order(List.of(item(EVENT_ID_1, ZONE_ID_1, 10.0)), true);
        when(activeOrderRepository.getByUserId(USER_ID)).thenReturn(order);
        when(paymentGateway.charge(any(PaymentRequestDTO.class))).thenReturn(paymentResult());
        when(ticketIssuer.issue(any(IssuanceRequestDTO.class))).thenReturn(issuanceResult(0));

        RuntimeException exception = assertThrows(RuntimeException.class, () ->
                checkoutService.checkoutMember(VALID_TOKEN, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN)
        );

        assertEquals(true, refundRequested.get());
        assertEquals(1, returnedTickets.get());
        assertEquals("Checkout failed, tickets returned to stock", exception.getMessage());
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

    @Test
    void GivenTicketIssuerThrowsException_WhenCheckout_ThenThrowExceptionAndRefundPaymentAndReturnTicketsToStock() {
        validSession();
        AtomicBoolean refundRequested = trackRefund();
        AtomicInteger returnedTickets = trackReturnedTickets(event1, ZONE_ID_1);

        ActiveOrder order = order(List.of(item(EVENT_ID_1, ZONE_ID_1, 10.0)), true);
        when(activeOrderRepository.getByUserId(USER_ID)).thenReturn(order);
        when(paymentGateway.charge(any(PaymentRequestDTO.class))).thenReturn(paymentResult());
        when(ticketIssuer.issue(any(IssuanceRequestDTO.class))).thenThrow(new RuntimeException("issuer failed"));

        RuntimeException exception = assertThrows(RuntimeException.class, () ->
                checkoutService.checkoutMember(VALID_TOKEN, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN)
        );

        assertEquals(true, refundRequested.get());
        assertEquals(1, returnedTickets.get());
        assertEquals("Checkout failed, tickets returned to stock", exception.getMessage());
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
    @Disabled("Notification failure should not cause checkout failure.")
    void GivenNotificationFailsAfterSuccessfulPurchase_WhenCheckout_ThenThrowExceptionAndRefundPaymentAndReturnTicketsToStock() {
        validSession();
        AtomicBoolean refundRequested = trackRefund();
        AtomicInteger returnedTickets = trackReturnedTickets(event1, ZONE_ID_1);

        ActiveOrder order = order(List.of(item(EVENT_ID_1, ZONE_ID_1, 10.0)), true);
        when(activeOrderRepository.getByUserId(USER_ID)).thenReturn(order);
        validPaymentAndIssuance(1);

        doThrow(new RuntimeException("notify failed"))
                .when(notificationService)
                .notifyPurchaseCompleted(anyInt(), anyDouble(), anyList());

        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> checkoutService.checkoutMember(VALID_TOKEN, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN));

        assertEquals(true, refundRequested.get());
        assertEquals(1, returnedTickets.get());
        assertEquals("Checkout failed, tickets returned to stock", exception.getMessage());
    }

    
    @Test
    void GivenTwoUsersCheckoutSameReservedTicketConcurrently_WhenCheckout_ThenOnlyOneCheckoutSucceeds()
            throws Exception {
        validSession();

        ActiveOrder order = order(List.of(item(EVENT_ID_1, ZONE_ID_1, 10.0)), true);
        when(activeOrderRepository.getByUserId(USER_ID)).thenReturn(order);
        when(paymentGateway.charge(any(PaymentRequestDTO.class))).thenReturn(paymentResult());

        when(ticketIssuer.issue(any(IssuanceRequestDTO.class)))
                .thenReturn(issuanceResult(1))
                .thenThrow(new RuntimeException("already sold"));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger callIndex = new AtomicInteger(0);

        Callable<Boolean> task = () -> {
            start.await();
            String uniqueKey = IDEMPOTENCY_KEY + "-" + callIndex.getAndIncrement();
            try {
                checkoutService.checkoutMember(VALID_TOKEN, uniqueKey, CURRENCY, PAYMENT_METHOD_TOKEN);
                return true;
            } catch (RuntimeException e) {
                return false;
            }
        };

        Future<Boolean> first = executor.submit(task);
        Future<Boolean> second = executor.submit(task);
        start.countDown();

        int successCount = 0;
        if (first.get()) {
            successCount++;
        }
        if (second.get()) {
            successCount++;
        }

        executor.shutdown();

        assertEquals(1, successCount);
    }

    
    @Test
    void GivenTwoUsersCheckoutLastTicketInZoneConcurrently_WhenCheckout_ThenOnlyOneCheckoutSucceeds() throws Exception {
        validSession();

        ActiveOrder order = order(List.of(item(EVENT_ID_1, ZONE_ID_1, 10.0)), true);
        when(activeOrderRepository.getByUserId(USER_ID)).thenReturn(order);
        when(paymentGateway.charge(any(PaymentRequestDTO.class))).thenReturn(paymentResult());

        when(ticketIssuer.issue(any(IssuanceRequestDTO.class)))
                .thenReturn(issuanceResult(1))
                .thenThrow(new RuntimeException("no stock"));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger callIndex = new AtomicInteger(0);

        Callable<Boolean> task = () -> {
            start.await();
            String uniqueKey = IDEMPOTENCY_KEY + "-" + callIndex.getAndIncrement();
            try {
                checkoutService.checkoutMember(VALID_TOKEN, uniqueKey, CURRENCY, PAYMENT_METHOD_TOKEN);
                return true;
            } catch (RuntimeException e) {
                return false;
            }
        };

        Future<Boolean> first = executor.submit(task);
        Future<Boolean> second = executor.submit(task);
        start.countDown();

        int successCount = 0;
        if (first.get()) {
            successCount++;
        }
        if (second.get()) {
            successCount++;
        }

        executor.shutdown();

        assertEquals(1, successCount);
    }

    @Test
    void GivenCheckoutFailsAfterPaymentSucceeded_WhenCheckout_ThenRefundIsRequested() {
        validSession();
        AtomicBoolean refundRequested = trackRefund();

        ActiveOrder order = order(List.of(item(EVENT_ID_1, ZONE_ID_1, 10.0)), true);
        when(activeOrderRepository.getByUserId(USER_ID)).thenReturn(order);
        when(paymentGateway.charge(any(PaymentRequestDTO.class))).thenReturn(paymentResult());
        when(ticketIssuer.issue(any(IssuanceRequestDTO.class))).thenReturn(null);

        assertThrows(RuntimeException.class, () ->
                checkoutService.checkoutMember(VALID_TOKEN, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN)
        );

        assertEquals(true, refundRequested.get());
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
    void GivenCheckoutFails_WhenCheckout_ThenTicketsAreReturnedToStock() {
        validSession();
        AtomicInteger returnedTickets = trackReturnedTickets(event1, ZONE_ID_1);

        ActiveOrder order = order(List.of(item(EVENT_ID_1, ZONE_ID_1, 10.0)), true);
        when(activeOrderRepository.getByUserId(USER_ID)).thenReturn(order);
        when(paymentGateway.charge(any(PaymentRequestDTO.class))).thenThrow(new RuntimeException("payment failed"));

        assertThrows(RuntimeException.class, () ->
                checkoutService.checkoutMember(VALID_TOKEN, IDEMPOTENCY_KEY, CURRENCY, PAYMENT_METHOD_TOKEN)
        );

        assertEquals(1, returnedTickets.get());
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
        }).when(notificationService).notifyPurchaseCompleted(anyInt(), anyDouble(), anyList());

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

    private CheckoutService checkoutServiceWith(IActiveOrderRepository realRepo) {
        return new CheckoutService(
                realRepo,
                eventRepository,
                ticketRepository,
                orderReceiptRepository,
                ticketIssuer,
                paymentGateway,
                notificationService,
                sessionManager,
                userRepository,
                companyRepository,
                systemAdminService,
                TestTransactions.noOpManager()
        );
    }

    @Test
    void GivenSuccessfulMemberCheckout_WhenBuyerChecksOutAgain_ThenSecondCheckoutSucceeds() {
        validSession();
        validPaymentAndIssuance(1);

        MemoryActiveOrderRepository realRepo = new MemoryActiveOrderRepository();
        CheckoutService service = checkoutServiceWith(realRepo);

        ActiveOrder firstOrder = new ActiveOrder(USER_ID);
        firstOrder.addStandingReservation(EVENT_ID_1, ZONE_ID_1, 1, 10.0, LocalDateTime.now());
        realRepo.save(firstOrder);

        service.checkoutMember(VALID_TOKEN, IDEMPOTENCY_KEY + "-1", CURRENCY, PAYMENT_METHOD_TOKEN);

        // The consumed cart must be gone so the buyer's next reservation/checkout starts clean.
        assertNull(realRepo.getByUserId(USER_ID));

        // Buyer comes back and reserves again on the same account, then checks out a second time.
        ActiveOrder secondOrder = new ActiveOrder(USER_ID);
        secondOrder.addStandingReservation(EVENT_ID_1, ZONE_ID_1, 1, 10.0, LocalDateTime.now());
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
        validPaymentAndIssuance(1);

        MemoryActiveOrderRepository realRepo = new MemoryActiveOrderRepository();
        CheckoutService service = checkoutServiceWith(realRepo);

        ActiveOrder firstOrder = ActiveOrder.forGuest(guestSessionId);
        firstOrder.addStandingReservation(EVENT_ID_1, ZONE_ID_1, 1, 10.0, LocalDateTime.now());
        realRepo.save(firstOrder);

        service.checkoutGuest(guestSessionId, guestEmail, IDEMPOTENCY_KEY + "-g1", CURRENCY, PAYMENT_METHOD_TOKEN, 30);

        assertTrue(realRepo.getBySessionId(guestSessionId).isEmpty());

        ActiveOrder secondOrder = ActiveOrder.forGuest(guestSessionId);
        secondOrder.addStandingReservation(EVENT_ID_1, ZONE_ID_1, 1, 10.0, LocalDateTime.now());
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




    // more acceptance tests:

    @Test
    @Disabled("Enable after seated reservation flow is wired end-to-end")
    void GivenReservedSeatedTickets_WhenCheckoutSucceeds_ThenTicketsHaveSeatNumbersAndSeatsBecomeSold() {
        
    }

    @Test
    @Disabled("Enable after checkout rollback supports seated zones")
    void GivenSeatedCheckoutPaymentSucceedsButIssuanceFails_WhenCheckout_ThenSeatsReturnToAvailable() {
        
    }

    
}