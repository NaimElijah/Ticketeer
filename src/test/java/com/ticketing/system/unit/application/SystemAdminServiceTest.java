package com.ticketing.system.unit.application;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import com.ticketing.system.Core.Application.dto.GlobalHistoryFiltersDTO;
import com.ticketing.system.Core.Application.dto.MarketControlRequestDTO;
import com.ticketing.system.Core.Application.dto.MarketStateDTO;
import com.ticketing.system.Core.Application.dto.PurchaseHistoryDTO;
import com.ticketing.system.identity.application.port.out.PasswordHasher;
import com.ticketing.system.Core.Application.interfaces.IPaymentGateway;
import com.ticketing.system.identity.application.port.out.SessionManager;
import com.ticketing.system.Core.Application.interfaces.ITicketIssuer;
import com.ticketing.system.Core.Application.services.SystemAdminService;
import com.ticketing.system.Core.Application.services.SystemIntegrityVerifier;
import com.ticketing.system.identity.domain.Admin;
import com.ticketing.system.identity.application.port.out.AdminRepository;
import com.ticketing.system.Core.Domain.Tickets.ITicketRepository;
import com.ticketing.system.Core.Domain.Tickets.Ticket;
import com.ticketing.system.Core.Domain.events.Event;
import com.ticketing.system.Core.Domain.events.IEventRepository;
import com.ticketing.system.organization.application.port.out.ProductionCompanyRepository;
import com.ticketing.system.identity.application.port.out.UserRepository;
import com.ticketing.system.shared.exception.ExternalServiceUnavailableException;
import com.ticketing.system.shared.exception.InitializationIntegrityException;
import com.ticketing.system.shared.exception.InvalidStateTransitionException;
import com.ticketing.system.shared.exception.MarketNotOpenException;
import com.ticketing.system.shared.exception.MissingDefaultAdminException;
import com.ticketing.system.shared.exception.UnauthorizedActionException;
import com.ticketing.system.Core.Domain.orders.IOrderReceiptRepository;
import com.ticketing.system.Core.Domain.orders.OrderReceipt;
import com.ticketing.system.Core.Domain.orders.ReceiptLine;

class SystemAdminServiceTest {

    private static final String ADMIN_TOKEN = "admin-token";
    private static final int ADMIN_USER_ID = 1;

    private SessionManager sessionManager;
    private AdminRepository adminRepository;
    private IOrderReceiptRepository orderReceiptRepository;
    private ITicketRepository ticketRepository;
    private IEventRepository eventRepository;
    private ProductionCompanyRepository companyRepository;
    private UserRepository userRepository;
    private PasswordHasher passwordHasher;
    private SystemIntegrityVerifier integrityVerifier;
    private SystemAdminService service;


    @BeforeEach
    void setUp() {
        sessionManager = mock(SessionManager.class);
        adminRepository = mock(AdminRepository.class);
        orderReceiptRepository = mock(IOrderReceiptRepository.class);
        ticketRepository = mock(ITicketRepository.class);
        eventRepository = mock(IEventRepository.class);
        companyRepository = mock(ProductionCompanyRepository.class);
        userRepository = mock(UserRepository.class);
        passwordHasher = mock(PasswordHasher.class);
        integrityVerifier = mock(SystemIntegrityVerifier.class);
        when(passwordHasher.hash(anyString())).thenReturn("hashed-password");
        service = new SystemAdminService(
                sessionManager,
                adminRepository,
                orderReceiptRepository,
                ticketRepository,
                eventRepository,
                companyRepository,
                userRepository,
                List.of(),
                List.of(),
                passwordHasher,
                integrityVerifier,
                "admin",
                "admin"
        );
        when(sessionManager.validateToken(ADMIN_TOKEN)).thenReturn(true);
        when(sessionManager.isAdminToken(ADMIN_TOKEN)).thenReturn(true);
        when(sessionManager.extractUserId(ADMIN_TOKEN)).thenReturn(ADMIN_USER_ID);
        when(adminRepository.findById(ADMIN_USER_ID)).thenReturn(new Admin(ADMIN_USER_ID, "admin", "hash", true));
    }

    // --- UC-1: initializePlatform ---

    @Test
    void givenAllServicesReachableAndAdminExists_whenInitializePlatform_thenSucceeds() {
        when(adminRepository.existsAny()).thenReturn(true);
        SystemAdminService svc = serviceWith(List.of(reachablePayment()), List.of(reachableIssuer()));
        assertDoesNotThrow(svc::initializePlatform);
    }

    @Test
    void givenNoReachablePaymentGateway_whenInitializePlatform_thenThrows() {
        SystemAdminService svc = serviceWith(List.of(), List.of(reachableIssuer()));
        assertThrows(ExternalServiceUnavailableException.class, svc::initializePlatform);
    }

    @Test
    void givenNoReachableTicketIssuer_whenInitializePlatform_thenThrows() {
        SystemAdminService svc = serviceWith(List.of(reachablePayment()), List.of());
        assertThrows(ExternalServiceUnavailableException.class, svc::initializePlatform);
    }

    @Test
    void givenNoAdmin_whenInitializePlatform_thenDefaultAdminCreated() {
        when(adminRepository.existsAny()).thenReturn(false, true);
        SystemAdminService svc = serviceWith(List.of(reachablePayment()), List.of(reachableIssuer()));
        svc.initializePlatform();
        verify(adminRepository).save(any(Admin.class));
    }

    @Test
    void givenNoAdminAndCreationDoesNotPersist_whenInitializePlatform_thenThrows() {
        // existsAny() stays false even after save() → auto-creation failed.
        when(adminRepository.existsAny()).thenReturn(false);
        SystemAdminService svc = serviceWith(List.of(reachablePayment()), List.of(reachableIssuer()));
        assertThrows(MissingDefaultAdminException.class, svc::initializePlatform);
    }

    @Test
    void givenAlreadyInitialized_whenInitializePlatformAgain_thenSecondCallIsNoOp() {
        when(adminRepository.existsAny()).thenReturn(true);
        SystemAdminService svc = serviceWith(List.of(reachablePayment()), List.of(reachableIssuer()));
        svc.initializePlatform();
        assertDoesNotThrow(svc::initializePlatform);
    }

    // --- UC-32: market lifecycle (open / close / view) ---

    @Test
    void givenInitializedPlatform_whenOpenMarket_thenStateOpen() {
        SystemAdminService svc = readyService();
        MarketStateDTO state = svc.openMarket(openRequest());
        assertEquals("OPEN", state.currentStatus());
        assertNotNull(state.lastOpenedAt());
        assertTrue(svc.isMarketOpen());
    }

    @Test
    void givenUninitializedPlatform_whenOpenMarket_thenThrowsMarketNotOpen() {
        SystemAdminService svc = serviceWith(List.of(reachablePayment()), List.of(reachableIssuer()));
        assertThrows(MarketNotOpenException.class, () -> svc.openMarket(openRequest()));
    }

    @Test
    void givenPaymentDownAtOpen_whenOpenMarket_thenThrowsAndStaysReady() {
        when(adminRepository.existsAny()).thenReturn(true);
        IPaymentGateway gateway = reachablePayment();
        SystemAdminService svc = serviceWith(List.of(gateway), List.of(reachableIssuer()));
        svc.initializePlatform();                          // READY
        when(gateway.verifyConnection()).thenReturn(false); // gateway drops before open
        assertThrows(ExternalServiceUnavailableException.class, () -> svc.openMarket(openRequest()));
        assertEquals("READY", svc.viewMarketState().currentStatus());
    }

    @Test
    void givenIssuerDownAtOpen_whenOpenMarket_thenThrowsAndStaysReady() {
        when(adminRepository.existsAny()).thenReturn(true);
        ITicketIssuer issuer = reachableIssuer();
        SystemAdminService svc = serviceWith(List.of(reachablePayment()), List.of(issuer));
        svc.initializePlatform();                          // READY
        when(issuer.verifyConnection()).thenReturn(false);  // issuer drops before open
        assertThrows(ExternalServiceUnavailableException.class, () -> svc.openMarket(openRequest()));
        assertEquals("READY", svc.viewMarketState().currentStatus());
    }

    @Test
    void givenNoAdminAtOpen_whenOpenMarket_thenThrowsAndStaysReady() {
        when(adminRepository.existsAny()).thenReturn(true);
        SystemAdminService svc = serviceWith(List.of(reachablePayment()), List.of(reachableIssuer()));
        svc.initializePlatform();                          // READY
        when(adminRepository.existsAny()).thenReturn(false); // admin removed before open
        assertThrows(InitializationIntegrityException.class, () -> svc.openMarket(openRequest()));
        assertEquals("READY", svc.viewMarketState().currentStatus());
    }

    @Test
    void givenNonAdminToken_whenOpenMarket_thenRejected() {
        when(sessionManager.validateToken("member-token")).thenReturn(true);
        when(sessionManager.isAdminToken("member-token")).thenReturn(false);
        SystemAdminService svc = readyService();
        MarketControlRequestDTO request = new MarketControlRequestDTO("OPEN", "x", "member-token");
        assertThrows(UnauthorizedActionException.class, () -> svc.openMarket(request));
    }

    @Test
    void givenOpenMarket_whenOpenAgain_thenStillOpenNoError() {
        SystemAdminService svc = readyService();
        svc.openMarket(openRequest());
        MarketStateDTO state = assertDoesNotThrow(() -> svc.openMarket(openRequest()));
        assertEquals("OPEN", state.currentStatus());
    }

    @Test
    void givenOpenMarket_whenCloseMarket_thenStateClosed() {
        SystemAdminService svc = readyService();
        svc.openMarket(openRequest());
        MarketStateDTO state = svc.closeMarket(closeRequest());
        assertEquals("CLOSED", state.currentStatus());
        assertFalse(svc.isMarketOpen());
    }

    @Test
    void givenClosedMarket_whenCloseAgain_thenStillClosedNoError() {
        SystemAdminService svc = readyService();
        svc.openMarket(openRequest());
        svc.closeMarket(closeRequest());
        MarketStateDTO state = assertDoesNotThrow(() -> svc.closeMarket(closeRequest()));
        assertEquals("CLOSED", state.currentStatus());
    }

    @Test
    void givenClosedMarket_whenOpenMarket_thenReopens() {
        SystemAdminService svc = readyService();
        svc.openMarket(openRequest());
        svc.closeMarket(closeRequest());
        MarketStateDTO state = svc.openMarket(openRequest());
        assertEquals("OPEN", state.currentStatus());
    }

    @Test
    void givenNeverOpened_whenCloseMarket_thenThrows() {
        SystemAdminService svc = readyService(); // READY, never opened
        assertThrows(InvalidStateTransitionException.class, () -> svc.closeMarket(closeRequest()));
    }

    @Test
    void givenNonAdminToken_whenCloseMarket_thenRejected() {
        when(sessionManager.validateToken("member-token")).thenReturn(true);
        when(sessionManager.isAdminToken("member-token")).thenReturn(false);
        SystemAdminService svc = readyService();
        svc.openMarket(openRequest());
        MarketControlRequestDTO request = new MarketControlRequestDTO("CLOSE", "x", "member-token");
        assertThrows(UnauthorizedActionException.class, () -> svc.closeMarket(request));
    }

    // --- #455: ensureMarketOpen (system self-heal — no admin token, recovers from a boot-time outage) ---

    @Test
    void givenReachableServices_whenEnsureMarketOpen_thenOpens() {
        when(adminRepository.existsAny()).thenReturn(true);
        SystemAdminService svc = serviceWith(List.of(reachablePayment()), List.of(reachableIssuer()));
        svc.ensureMarketOpen();   // UNINITIALIZED -> READY -> OPEN in one shot
        assertTrue(svc.isMarketOpen());
    }

    @Test
    void givenServicesDownThenUp_whenEnsureMarketOpenTwice_thenSelfHealsToOpen() {
        when(adminRepository.existsAny()).thenReturn(true);
        IPaymentGateway gateway = mock(IPaymentGateway.class);
        when(gateway.verifyConnection()).thenReturn(false);   // WSEP cold / unreachable at boot
        SystemAdminService svc = serviceWith(List.of(gateway), List.of(reachableIssuer()));

        svc.ensureMarketOpen();                               // can't reach payment -> stays closed, no throw
        assertFalse(svc.isMarketOpen());

        when(gateway.verifyConnection()).thenReturn(true);    // WSEP recovers
        svc.ensureMarketOpen();                               // self-heal -> opens
        assertTrue(svc.isMarketOpen());
    }

    @Test
    void givenAdminClosedMarket_whenEnsureMarketOpen_thenStaysClosed() {
        SystemAdminService svc = readyService();
        svc.openMarket(openRequest());
        svc.closeMarket(closeRequest());                      // admin deliberately closes the market
        svc.ensureMarketOpen();                               // must NOT auto-reopen a deliberate close
        assertFalse(svc.isMarketOpen());
        assertEquals("CLOSED", svc.viewMarketState().currentStatus());
    }

    @Test
    void givenNoReachableServices_whenEnsureMarketOpen_thenNeverThrowsAndStaysClosed() {
        SystemAdminService svc = serviceWith(List.of(), List.of());
        assertDoesNotThrow(svc::ensureMarketOpen);            // self-heal must never throw out to its caller
        assertFalse(svc.isMarketOpen());
    }

    @Test
    void givenInitializedPlatform_whenViewMarketState_thenReportsHealth() {
        SystemAdminService svc = readyService();
        MarketStateDTO state = svc.viewMarketState();
        assertEquals("READY", state.currentStatus());
        assertTrue(state.paymentGatewayHealthy());
        assertTrue(state.ticketIssuerHealthy());
        assertTrue(state.defaultAdminPresent());
    }

    @Test @Disabled("UC-31: viewGlobalHistory admin-only RBAC")
    void givenNonAdmin_whenViewGlobalHistory_thenRejected() {}

    // --- UC-31: viewGlobalHistory ---

    @Test
    void givenNoMatchingReceipts_whenViewGlobalHistory_thenReturnsEmptyRecords() {
        GlobalHistoryFiltersDTO filters = new GlobalHistoryFiltersDTO(null, null, null, null, null);
        when(orderReceiptRepository.findGlobal(filters)).thenReturn(List.of());

        List<PurchaseHistoryDTO> result = service.viewGlobalHistory(ADMIN_TOKEN, filters);

        assertEquals(1, result.size());
        assertTrue(result.get(0).records().isEmpty());
    }

    @Test
    void givenOneReceipt_whenViewGlobalHistory_thenRecordMapped() {
        int ticketId = 1;
        double price = 99.0;
        ReceiptLine line1 = new ReceiptLine(1, 25.0, 25, 1, null, LocalDateTime.now());
        OrderReceipt receipt = OrderReceipt.forMember(11, 1, price, List.of(line1));
        Ticket ticket = new Ticket(1, 1, 11, null, price, ticketId, "BARCODE");
        Event event = mock(Event.class);
        when(event.getName()).thenReturn("Rock Night");

        GlobalHistoryFiltersDTO filters = new GlobalHistoryFiltersDTO(1, null, null, null, null);
        when(orderReceiptRepository.findGlobal(filters)).thenReturn(List.of(receipt));
        when(ticketRepository.findByOrderReceiptId(receipt.getId())).thenReturn(List.of(ticket));
        when(eventRepository.findById(1)).thenReturn(event);

        List<PurchaseHistoryDTO> result = service.viewGlobalHistory(ADMIN_TOKEN, filters);

        assertEquals(1, result.size());
        List<PurchaseHistoryDTO.PurchaseRecordDTO> records = result.get(0).records();
        assertEquals(1, records.size());
        PurchaseHistoryDTO.PurchaseRecordDTO record = records.get(0);
        assertEquals(price, record.totalPaid());
        assertEquals(1, record.tickets().size());
        assertEquals(ticketId, record.tickets().get(0).ticketId());
    }




    @Test
    void givenMultipleReceipts_whenViewGlobalHistory_thenAllRecordsMapped() {
        ReceiptLine line1 = new ReceiptLine(1, 25.0, 25, 1, null, LocalDateTime.now());
        ReceiptLine line2 = new ReceiptLine(2, 25.0, 3,  1, "A2", LocalDateTime.now());
        OrderReceipt receipt1 = OrderReceipt.forMember(11, 1, 50.0, List.of(line1));
        OrderReceipt receipt2 = OrderReceipt.forMember(12, 2, 75.0, List.of(line2));
        Event event = mock(Event.class);
        when(event.getName()).thenReturn("Jazz Night");

        GlobalHistoryFiltersDTO filters = new GlobalHistoryFiltersDTO(null, null, null, null, null);
        when(orderReceiptRepository.findGlobal(filters)).thenReturn(List.of(receipt1, receipt2));
        when(ticketRepository.findByOrderReceiptId(anyInt())).thenReturn(List.of());
        when(eventRepository.findById(anyInt())).thenReturn(event);

        List<PurchaseHistoryDTO> result = service.viewGlobalHistory(ADMIN_TOKEN, filters);

        assertEquals(1, result.size());
        assertEquals(2, result.get(0).records().size());
    }

    @Test
    void givenFilters_whenViewGlobalHistory_thenFiltersPassedToRepository() {
        GlobalHistoryFiltersDTO filters = new GlobalHistoryFiltersDTO(
                7, 3, List.of(10, 11),
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 12, 31));
        // normalizeGlobalHistoryFilters resolves companyId → null and intersects eventIds
        GlobalHistoryFiltersDTO expectedFilters = new GlobalHistoryFiltersDTO(
                7, null, List.of(10, 11),
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 12, 31));
        when(eventRepository.findIdsByCompany(3)).thenReturn(List.of(10, 11));
        when(orderReceiptRepository.findGlobal(expectedFilters)).thenReturn(List.of());

        service.viewGlobalHistory(ADMIN_TOKEN, filters);

        verify(orderReceiptRepository).findGlobal(expectedFilters);
    }

    @Test
    void givenReceiptWithMultipleTickets_whenViewGlobalHistory_thenTotalPaidIsSumOfTicketPrices() {
        ReceiptLine line1 = new ReceiptLine(1, 50.0, 25, 1, null, LocalDateTime.now());
        ReceiptLine line2 = new ReceiptLine(2, 25.0, 3,  1, "A2", LocalDateTime.now());
        OrderReceipt receipt = OrderReceipt.forMember(11, 1, 75.0, List.of(line1, line2));
        Ticket t1 = new Ticket(1, 1, 11, null, 30.0, 1, "B1");
        Ticket t2 = new Ticket(1, 1, 11, null, 45.0, 2, "B2");
        Event event = mock(Event.class);
        when(event.getName()).thenReturn("Pop Show");

        GlobalHistoryFiltersDTO filters = new GlobalHistoryFiltersDTO(null, null, null, null, null);
        when(orderReceiptRepository.findGlobal(filters)).thenReturn(List.of(receipt));
        when(ticketRepository.findByOrderReceiptId(1)).thenReturn(List.of(t1, t2));
        when(eventRepository.findById(anyInt())).thenReturn(event);

        List<PurchaseHistoryDTO> result = service.viewGlobalHistory(ADMIN_TOKEN, filters);

        PurchaseHistoryDTO.PurchaseRecordDTO record = result.get(0).records().get(0);
        assertEquals(75.0, record.totalPaid());
        assertEquals(2, record.tickets().size());
    }


    // --- helpers ---

    // An initialized (READY) service with one reachable gateway + issuer and an
    // existing admin — the precondition for opening the market.
    private SystemAdminService readyService() {
        when(adminRepository.existsAny()).thenReturn(true);
        SystemAdminService svc = serviceWith(List.of(reachablePayment()), List.of(reachableIssuer()));
        svc.initializePlatform();
        return svc;
    }

    private MarketControlRequestDTO openRequest() {
        return new MarketControlRequestDTO("OPEN", "test open", ADMIN_TOKEN);
    }

    private MarketControlRequestDTO closeRequest() {
        return new MarketControlRequestDTO("CLOSE", "test close", ADMIN_TOKEN);
    }

    private SystemAdminService serviceWith(List<IPaymentGateway> gateways, List<ITicketIssuer> issuers) {
        return new SystemAdminService(
                sessionManager, adminRepository, orderReceiptRepository,
                ticketRepository, eventRepository, companyRepository, userRepository,
                gateways, issuers, passwordHasher,
                integrityVerifier, "admin", "admin");
    }

    private IPaymentGateway reachablePayment() {
        IPaymentGateway gateway = mock(IPaymentGateway.class);
        when(gateway.verifyConnection()).thenReturn(true);
        return gateway;
    }

    private ITicketIssuer reachableIssuer() {
        ITicketIssuer issuer = mock(ITicketIssuer.class);
        when(issuer.verifyConnection()).thenReturn(true);
        return issuer;
    }
}
