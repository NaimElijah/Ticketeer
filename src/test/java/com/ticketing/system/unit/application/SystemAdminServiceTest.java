package com.ticketing.system.unit.application;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import com.ticketing.system.governance.config.PlatformAdminProperties;
import com.ticketing.system.shared.dto.GlobalHistoryFiltersDTO;
import com.ticketing.system.shared.dto.MarketControlRequestDTO;
import com.ticketing.system.shared.dto.MarketStateDTO;
import com.ticketing.system.sales.application.dto.PurchaseHistoryDTO;
import com.ticketing.system.identity.application.port.out.PasswordHasher;
import com.ticketing.system.sales.application.port.out.PaymentGateway;
import com.ticketing.system.identity.application.port.out.SessionManager;
import com.ticketing.system.sales.application.port.out.TicketIssuer;
import com.ticketing.system.governance.application.service.SystemAdminService;
import com.ticketing.system.governance.application.service.SystemIntegrityVerifier;
import com.ticketing.system.identity.domain.Admin;
import com.ticketing.system.identity.application.port.out.AdminRepository;
import com.ticketing.system.sales.application.port.out.TicketRepository;
import com.ticketing.system.sales.domain.Ticket;
import com.ticketing.system.catalog.domain.Event;
import com.ticketing.system.catalog.application.port.in.CatalogEventDisplayPort;
import com.ticketing.system.catalog.application.port.out.EventRepository;
import com.ticketing.system.organization.application.port.out.ProductionCompanyRepository;
import com.ticketing.system.identity.application.port.out.UserRepository;
import com.ticketing.system.shared.exception.ExternalServiceUnavailableException;
import com.ticketing.system.shared.exception.InitializationIntegrityException;
import com.ticketing.system.shared.exception.InvalidStateTransitionException;
import com.ticketing.system.shared.exception.InvalidTokenException;
import com.ticketing.system.shared.exception.MarketNotOpenException;
import com.ticketing.system.shared.exception.MissingDefaultAdminException;
import com.ticketing.system.shared.exception.SessionExpiredException;
import com.ticketing.system.shared.exception.SystemIntegrityViolationException;
import com.ticketing.system.shared.exception.UnauthorizedActionException;
import com.ticketing.system.sales.application.port.out.OrderReceiptRepository;
import com.ticketing.system.sales.domain.OrderReceipt;
import com.ticketing.system.sales.domain.ReceiptLine;
import com.ticketing.system.sales.domain.TransactionRecord;
import com.ticketing.system.testutil.LogCapture;

import ch.qos.logback.classic.Level;

class SystemAdminServiceTest {

    private static final String ADMIN_TOKEN = "admin-token";
    // Lifecycle transitions are stamped from the injected clock, as UTC wall time (ADR-008).
    private static final Instant T0 = Instant.parse("2027-07-15T17:00:00Z");
    private static final int ADMIN_USER_ID = 1;

    private SessionManager sessionManager;
    private AdminRepository adminRepository;
    private OrderReceiptRepository orderReceiptRepository;
    private TicketRepository ticketRepository;
    private EventRepository eventRepository;
    private CatalogEventDisplayPort eventDisplayPort;
    private ProductionCompanyRepository companyRepository;
    private UserRepository userRepository;
    private PasswordHasher passwordHasher;
    private SystemIntegrityVerifier integrityVerifier;
    private SystemAdminService service;


    @BeforeEach
    void setUp() {
        sessionManager = mock(SessionManager.class);
        adminRepository = mock(AdminRepository.class);
        orderReceiptRepository = mock(OrderReceiptRepository.class);
        ticketRepository = mock(TicketRepository.class);
        eventRepository = mock(EventRepository.class);
        eventDisplayPort = mock(CatalogEventDisplayPort.class);
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
                eventDisplayPort,
                companyRepository,
                userRepository,
                List.of(),
                List.of(),
                passwordHasher,
                integrityVerifier,
                new PlatformAdminProperties("admin", "admin"),
                Clock.fixed(T0, ZoneOffset.UTC)
        );
        // An admin token passes the admin readers only; the member readers reject it (#185).
        when(sessionManager.validateAdminToken(ADMIN_TOKEN)).thenReturn(true);
        when(sessionManager.isAdminToken(ADMIN_TOKEN)).thenReturn(true);
        when(sessionManager.extractAdminId(ADMIN_TOKEN)).thenReturn(ADMIN_USER_ID);
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
        when(adminRepository.existsAny()).thenReturn(true);   // admin present, so only WSEP fails
        SystemAdminService svc = serviceWith(List.of(), List.of(reachableIssuer()));
        assertThrows(ExternalServiceUnavailableException.class, svc::initializePlatform);
    }

    @Test
    void givenNoReachableTicketIssuer_whenInitializePlatform_thenThrows() {
        when(adminRepository.existsAny()).thenReturn(true);   // admin present, so only WSEP fails
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

    // ADR-003: a WSEP outage is the only self-healing init failure, so it must not hide a fatal one.
    @Test
    void GivenAdminCannotBePersistedAndWsepDown_WhenInitializePlatform_ThenMissingDefaultAdmin() {
        when(adminRepository.existsAny()).thenReturn(false);
        SystemAdminService svc = serviceWith(List.of(), List.of());
        assertThrows(MissingDefaultAdminException.class, svc::initializePlatform);
    }

    // Two instances booting against one empty admin table: the loser's insert fails, but an admin exists.
    @Test
    void GivenAnotherInstanceCreatedTheAdmin_WhenDefaultAdminSaveFails_ThenInitializationSucceeds() {
        when(adminRepository.existsAny()).thenReturn(false, true);
        doThrow(new RuntimeException("duplicate key value violates unique constraint \"admins_pkey\""))
                .when(adminRepository).save(any(Admin.class));
        SystemAdminService svc = serviceWith(List.of(reachablePayment()), List.of(reachableIssuer()));
        assertDoesNotThrow(svc::initializePlatform);
    }

    @Test
    void GivenDefaultAdminSaveFailsAndStillNoAdmin_WhenInitializePlatform_ThenMissingDefaultAdmin() {
        when(adminRepository.existsAny()).thenReturn(false);
        doThrow(new RuntimeException("database unavailable")).when(adminRepository).save(any(Admin.class));
        SystemAdminService svc = serviceWith(List.of(reachablePayment()), List.of(reachableIssuer()));
        assertThrows(MissingDefaultAdminException.class, svc::initializePlatform);
    }

    @Test
    void GivenCorruptStateAndWsepDown_WhenInitializePlatform_ThenIntegrityViolation() {
        when(adminRepository.existsAny()).thenReturn(true);
        doThrow(new SystemIntegrityViolationException("active company 7 has no owner"))
                .when(integrityVerifier).verify();
        SystemAdminService svc = serviceWith(List.of(), List.of());
        assertThrows(SystemIntegrityViolationException.class, svc::initializePlatform);
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
    void GivenFixedClock_WhenOpenMarket_ThenLastOpenedAtIsTheClock() {
        SystemAdminService svc = readyService();

        MarketStateDTO state = svc.openMarket(openRequest());

        assertEquals(LocalDateTime.ofInstant(T0, ZoneOffset.UTC), state.lastOpenedAt());
    }

    @Test
    void givenUninitializedPlatform_whenOpenMarket_thenThrowsMarketNotOpen() {
        SystemAdminService svc = serviceWith(List.of(reachablePayment()), List.of(reachableIssuer()));
        assertThrows(MarketNotOpenException.class, () -> svc.openMarket(openRequest()));
    }

    @Test
    void givenPaymentDownAtOpen_whenOpenMarket_thenThrowsAndStaysReady() {
        when(adminRepository.existsAny()).thenReturn(true);
        PaymentGateway gateway = reachablePayment();
        SystemAdminService svc = serviceWith(List.of(gateway), List.of(reachableIssuer()));
        svc.initializePlatform();                          // READY
        when(gateway.verifyConnection()).thenReturn(false); // gateway drops before open
        assertThrows(ExternalServiceUnavailableException.class, () -> svc.openMarket(openRequest()));
        assertEquals("READY", svc.viewMarketState(ADMIN_TOKEN).currentStatus());
    }

    @Test
    void givenIssuerDownAtOpen_whenOpenMarket_thenThrowsAndStaysReady() {
        when(adminRepository.existsAny()).thenReturn(true);
        TicketIssuer issuer = reachableIssuer();
        SystemAdminService svc = serviceWith(List.of(reachablePayment()), List.of(issuer));
        svc.initializePlatform();                          // READY
        when(issuer.verifyConnection()).thenReturn(false);  // issuer drops before open
        assertThrows(ExternalServiceUnavailableException.class, () -> svc.openMarket(openRequest()));
        assertEquals("READY", svc.viewMarketState(ADMIN_TOKEN).currentStatus());
    }

    @Test
    void givenNoAdminAtOpen_whenOpenMarket_thenThrowsAndStaysReady() {
        when(adminRepository.existsAny()).thenReturn(true);
        SystemAdminService svc = serviceWith(List.of(reachablePayment()), List.of(reachableIssuer()));
        svc.initializePlatform();                          // READY
        when(adminRepository.existsAny()).thenReturn(false); // admin removed before open
        assertThrows(InitializationIntegrityException.class, () -> svc.openMarket(openRequest()));
        assertEquals("READY", svc.viewMarketState(ADMIN_TOKEN).currentStatus());
    }

    @Test
    void givenNonAdminToken_whenOpenMarket_thenRejected() {
        stubLiveMemberToken("member-token");
        SystemAdminService svc = readyService();
        MarketControlRequestDTO request = new MarketControlRequestDTO("OPEN", "x", "member-token");
        assertThrows(InvalidTokenException.class, () -> svc.openMarket(request));
        assertEquals("READY", svc.viewMarketState(ADMIN_TOKEN).currentStatus());
    }

    @Test
    void givenNoToken_whenOpenMarket_thenUnauthorized() {
        SystemAdminService svc = readyService();
        MarketControlRequestDTO request = new MarketControlRequestDTO("OPEN", "x", null);
        assertThrows(UnauthorizedActionException.class, () -> svc.openMarket(request));
        assertEquals("READY", svc.viewMarketState(ADMIN_TOKEN).currentStatus());
    }

    @Test
    void givenAdminTokenWhoseAdminNoLongerExists_whenOpenMarket_thenUnauthorized() {
        when(adminRepository.findById(ADMIN_USER_ID)).thenReturn(null);
        SystemAdminService svc = readyService();
        assertThrows(UnauthorizedActionException.class, () -> svc.openMarket(openRequest()));
        // The market view is admin-gated too, so read the state as an admin who is on record.
        when(adminRepository.findById(ADMIN_USER_ID)).thenReturn(new Admin(ADMIN_USER_ID, "admin", "hash", true));
        assertEquals("READY", svc.viewMarketState(ADMIN_TOKEN).currentStatus());
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
        stubLiveMemberToken("member-token");
        SystemAdminService svc = readyService();
        svc.openMarket(openRequest());
        MarketControlRequestDTO request = new MarketControlRequestDTO("CLOSE", "x", "member-token");
        assertThrows(InvalidTokenException.class, () -> svc.closeMarket(request));
        assertTrue(svc.isMarketOpen());
    }

    // A live member token, as the real SessionManager answers for it: valid on the member readers,
    // rejected by the admin readers (#185).
    private void stubLiveMemberToken(String token) {
        when(sessionManager.validateToken(token)).thenReturn(true);
        when(sessionManager.extractUserId(token)).thenReturn(ADMIN_USER_ID);
        when(sessionManager.validateAdminToken(token)).thenThrow(new InvalidTokenException("session not found"));
        when(sessionManager.extractAdminId(token)).thenThrow(new InvalidTokenException("session not found"));
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
        PaymentGateway gateway = mock(PaymentGateway.class);
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
        assertEquals("CLOSED", svc.viewMarketState(ADMIN_TOKEN).currentStatus());
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
        MarketStateDTO state = svc.viewMarketState(ADMIN_TOKEN);
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
        OrderReceipt receipt = OrderReceipt.forMember(11, 1, price, List.of(line1), LocalDateTime.now());
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
        OrderReceipt receipt1 = OrderReceipt.forMember(11, 1, 50.0, List.of(line1), LocalDateTime.now());
        OrderReceipt receipt2 = OrderReceipt.forMember(12, 2, 75.0, List.of(line2), LocalDateTime.now());
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
        OrderReceipt receipt = OrderReceipt.forMember(11, 1, 75.0, List.of(line1, line2), LocalDateTime.now());
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

    // #121 — company sales history hides payment transactions; the admin view must keep them,
    // even when filtered to one event of an order that spans several.
    @Test
    void GivenEventFilteredGlobalHistory_WhenViewGlobalHistory_ThenEveryTransactionIsListed() {
        LocalDateTime purchasedAt = LocalDateTime.of(2026, 1, 15, 20, 0);
        ReceiptLine filteredEventLine = new ReceiptLine(1, 100.0, 25, 1, null, purchasedAt);
        ReceiptLine otherEventLine = new ReceiptLine(2, 200.0, 3, 1, "A2", purchasedAt);
        OrderReceipt receipt = OrderReceipt.forMember(11, 1, 300.0, List.of(filteredEventLine, otherEventLine),
                LocalDateTime.now(),
                List.of(TransactionRecord.paymentCharge(42, "test-gateway", 300.0, "ILS", purchasedAt),
                        TransactionRecord.ticketIssuance("ISS-42", "test-issuer", purchasedAt)));

        GlobalHistoryFiltersDTO filters = new GlobalHistoryFiltersDTO(null, null, List.of(25), null, null);
        when(orderReceiptRepository.findGlobal(filters)).thenReturn(List.of(receipt));
        when(ticketRepository.findByOrderReceiptId(anyInt())).thenReturn(List.of());

        PurchaseHistoryDTO.PurchaseRecordDTO record =
                service.viewGlobalHistory(ADMIN_TOKEN, filters).get(0).records().get(0);

        assertEquals(1, record.tickets().size());
        assertEquals(2, record.transactions().size());
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

    private SystemAdminService serviceWith(List<PaymentGateway> gateways, List<TicketIssuer> issuers) {
        return new SystemAdminService(
                sessionManager, adminRepository, orderReceiptRepository,
                ticketRepository, eventRepository, eventDisplayPort, companyRepository, userRepository,
                gateways, issuers, passwordHasher,
                integrityVerifier, new PlatformAdminProperties("admin", "admin"), Clock.fixed(T0, ZoneOffset.UTC));
    }

    private PaymentGateway reachablePayment() {
        PaymentGateway gateway = mock(PaymentGateway.class);
        when(gateway.verifyConnection()).thenReturn(true);
        return gateway;
    }

    private TicketIssuer reachableIssuer() {
        TicketIssuer issuer = mock(TicketIssuer.class);
        when(issuer.verifyConnection()).thenReturn(true);
        return issuer;
    }

    // ---- Defect 3: the market state view probes WSEP on every call (#187) ----

    // The dashboard polls the view every 5 s, and a probe can take a minute against a hanging WSEP:
    // the view and the synchronized close report the result of the last init, open or self-heal probe.
    @Test
    void GivenReadyPlatform_WhenViewMarketState_ThenNoConnectionCheckIsMade() {
        when(adminRepository.existsAny()).thenReturn(true);
        PaymentGateway gateway = reachablePayment();
        TicketIssuer issuer = reachableIssuer();
        SystemAdminService svc = serviceWith(List.of(gateway), List.of(issuer));
        svc.initializePlatform();
        clearInvocations(gateway, issuer);

        svc.viewMarketState(ADMIN_TOKEN);

        verify(gateway, never()).verifyConnection();
        verify(issuer, never()).verifyConnection();
    }

    @Test
    void GivenOpenMarket_WhenCloseMarket_ThenNoConnectionCheckIsMade() {
        when(adminRepository.existsAny()).thenReturn(true);
        PaymentGateway gateway = reachablePayment();
        TicketIssuer issuer = reachableIssuer();
        SystemAdminService svc = serviceWith(List.of(gateway), List.of(issuer));
        svc.initializePlatform();
        svc.openMarket(openRequest());
        clearInvocations(gateway, issuer);

        svc.closeMarket(closeRequest());

        verify(gateway, never()).verifyConnection();
        verify(issuer, never()).verifyConnection();
    }

    @Test
    void GivenMarketOpenedWithBothServicesReachable_WhenViewMarketStateAfterThemDrop_ThenTheOpenProbeIsReported() {
        when(adminRepository.existsAny()).thenReturn(true);
        PaymentGateway gateway = reachablePayment();
        TicketIssuer issuer = reachableIssuer();
        SystemAdminService svc = serviceWith(List.of(gateway), List.of(issuer));
        svc.initializePlatform();
        svc.openMarket(openRequest());
        when(gateway.verifyConnection()).thenThrow(new ExternalServiceUnavailableException("WSEP hangs"));
        when(issuer.verifyConnection()).thenReturn(false);

        MarketStateDTO state = svc.viewMarketState(ADMIN_TOKEN);

        assertTrue(state.paymentGatewayHealthy(), "the view reports the payment probe of the last open");
        assertTrue(state.ticketIssuerHealthy(), "the view reports the issuer probe of the last open");
    }

    // The DTO has no probe time, so a service nobody has checked yet is reported unreachable, never healthy.
    @Test
    void GivenNoProbeHasRunYet_WhenViewMarketState_ThenNeitherServiceIsReportedReachable() {
        when(adminRepository.existsAny()).thenReturn(true);
        PaymentGateway gateway = reachablePayment();
        TicketIssuer issuer = reachableIssuer();
        SystemAdminService svc = serviceWith(List.of(gateway), List.of(issuer));

        MarketStateDTO state = svc.viewMarketState(ADMIN_TOKEN);

        assertFalse(state.paymentGatewayHealthy(), "no probe has checked the payment service yet");
        assertFalse(state.ticketIssuerHealthy(), "no probe has checked the ticket issuer yet");
        verify(gateway, never()).verifyConnection();
        verify(issuer, never()).verifyConnection();
    }

    // ---- Defect 4: viewMarketState has no admin gate (#187) ----

    // Refusals match openMarket's: the admin readers' own exceptions, or UnauthorizedActionException.
    @Test
    void GivenLiveMemberToken_WhenViewMarketState_ThenInvalidToken() {
        stubLiveMemberToken("member-token");
        SystemAdminService svc = readyService();

        assertThrows(InvalidTokenException.class, () -> svc.viewMarketState("member-token"));
    }

    @Test
    void GivenNoToken_WhenViewMarketState_ThenUnauthorized() {
        SystemAdminService svc = readyService();

        assertThrows(UnauthorizedActionException.class, () -> svc.viewMarketState(null));
    }

    @Test
    void GivenExpiredAdminToken_WhenViewMarketState_ThenSessionExpired() {
        when(sessionManager.validateAdminToken("expired-admin-token")).thenThrow(new SessionExpiredException());
        when(sessionManager.extractAdminId("expired-admin-token")).thenThrow(new SessionExpiredException());
        SystemAdminService svc = readyService();

        assertThrows(SessionExpiredException.class, () -> svc.viewMarketState("expired-admin-token"));
    }

    @Test
    void GivenAdminTokenWhoseAdminNoLongerExists_WhenViewMarketState_ThenUnauthorized() {
        when(adminRepository.findById(ADMIN_USER_ID)).thenReturn(null);
        SystemAdminService svc = readyService();

        assertThrows(UnauthorizedActionException.class, () -> svc.viewMarketState(ADMIN_TOKEN));
    }

    @Test
    void GivenLiveAdminToken_WhenViewMarketState_ThenStateReturned() {
        SystemAdminService svc = readyService();

        assertEquals("READY", svc.viewMarketState(ADMIN_TOKEN).currentStatus());
    }

    // ---- Defect 5: ensureMarketOpen hides failures (#187) ----

    // Only a WSEP outage self-heals (ADR-003); any other failure repeats every 30 s until someone acts.
    @Test
    void GivenWsepUnreachable_WhenEnsureMarketOpen_ThenWarnsAndLogsNoError() {
        when(adminRepository.existsAny()).thenReturn(true);
        PaymentGateway unreachable = mock(PaymentGateway.class);
        when(unreachable.verifyConnection()).thenReturn(false);
        SystemAdminService svc = serviceWith(List.of(unreachable), List.of(reachableIssuer()));

        try (LogCapture log = LogCapture.of(SystemAdminService.class)) {
            assertDoesNotThrow(svc::ensureMarketOpen);

            assertTrue(log.messagesAt(Level.WARN).stream().anyMatch(m -> m.contains("no reachable payment service")),
                    "expected a WARN naming the outage, got " + log.messagesAt(Level.WARN));
            assertTrue(log.messagesAt(Level.ERROR).isEmpty(),
                    "a WSEP outage self-heals and is no ERROR, got " + log.messagesAt(Level.ERROR));
        }
    }

    @Test
    void GivenIntegrityViolation_WhenEnsureMarketOpen_ThenLogsErrorWithTheException() {
        when(adminRepository.existsAny()).thenReturn(true);
        doThrow(new SystemIntegrityViolationException("active company 7 has no owner"))
                .when(integrityVerifier).verify();
        SystemAdminService svc = serviceWith(List.of(reachablePayment()), List.of(reachableIssuer()));

        assertSelfHealLogsErrorWith(svc, SystemIntegrityViolationException.class);
    }

    @Test
    void GivenDefaultAdminCannotBeCreated_WhenEnsureMarketOpen_ThenLogsErrorWithTheException() {
        when(adminRepository.existsAny()).thenReturn(false);
        doThrow(new DataAccessResourceFailureException("database unavailable"))
                .when(adminRepository).save(any(Admin.class));
        SystemAdminService svc = serviceWith(List.of(reachablePayment()), List.of(reachableIssuer()));

        assertSelfHealLogsErrorWith(svc, MissingDefaultAdminException.class);
    }

    @Test
    void GivenUnexpectedNullPointer_WhenEnsureMarketOpen_ThenLogsErrorWithTheException() {
        when(adminRepository.existsAny()).thenThrow(new NullPointerException("admin store not wired"));
        SystemAdminService svc = serviceWith(List.of(reachablePayment()), List.of(reachableIssuer()));

        assertSelfHealLogsErrorWith(svc, NullPointerException.class);
    }

    private static void assertSelfHealLogsErrorWith(SystemAdminService svc, Class<? extends Throwable> expected) {
        try (LogCapture log = LogCapture.of(SystemAdminService.class)) {
            assertDoesNotThrow(svc::ensureMarketOpen);

            assertTrue(log.throwableClassNamesAt(Level.ERROR).contains(expected.getName()),
                    "expected an ERROR carrying " + expected.getSimpleName() + ", got ERROR throwables "
                            + log.throwableClassNamesAt(Level.ERROR) + " and WARN messages "
                            + log.messagesAt(Level.WARN));
        }
    }

    // ---- Defect 6: causes are dropped (#187) ----

    @Test
    void GivenDefaultAdminSaveFails_WhenInitializePlatform_ThenMissingDefaultAdminCarriesTheCause() {
        when(adminRepository.existsAny()).thenReturn(false);
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("database unavailable");
        doThrow(failure).when(adminRepository).save(any(Admin.class));
        SystemAdminService svc = serviceWith(List.of(reachablePayment()), List.of(reachableIssuer()));

        MissingDefaultAdminException thrown = assertThrows(MissingDefaultAdminException.class, svc::initializePlatform);

        assertSame(failure, thrown.getCause(), "the save failure must be kept as the cause");
    }

    // ---- Review follow-up 1: live WSEP reachability while the market is open (#187) ----

    private static final LocalDateTime PROBED_AT = LocalDateTime.ofInstant(T0, ZoneOffset.UTC);

    @Test
    void GivenPaymentDownAtInit_WhenViewMarketState_ThenPaymentIsReportedUnreachableAtTheProbeTime() {
        when(adminRepository.existsAny()).thenReturn(true);
        PaymentGateway gateway = mock(PaymentGateway.class);
        when(gateway.verifyConnection()).thenReturn(false);
        SystemAdminService svc = serviceWith(List.of(gateway), List.of(reachableIssuer()));
        assertThrows(ExternalServiceUnavailableException.class, svc::initializePlatform);

        MarketStateDTO state = svc.viewMarketState(ADMIN_TOKEN);

        assertFalse(state.paymentGatewayHealthy(), "the failed payment probe is reported");
        assertEquals(PROBED_AT, state.lastProbedAt(), "the probe time is reported with it");
    }

    // A down payment service must not leave the issuer's state stale or unchecked: both are probed.
    @Test
    void GivenPaymentDownAtInit_WhenInitializePlatform_ThenTheIssuerIsProbedAndReportedToo() {
        when(adminRepository.existsAny()).thenReturn(true);
        PaymentGateway gateway = mock(PaymentGateway.class);
        when(gateway.verifyConnection()).thenReturn(false);
        TicketIssuer issuer = reachableIssuer();
        SystemAdminService svc = serviceWith(List.of(gateway), List.of(issuer));

        assertThrows(ExternalServiceUnavailableException.class, svc::initializePlatform);

        verify(issuer).verifyConnection();
        assertTrue(svc.viewMarketState(ADMIN_TOKEN).ticketIssuerHealthy(), "the issuer answered the same probe");
    }

    @Test
    void GivenOpenMarket_WhenWsepDropsAndReachabilityIsRefreshed_ThenTheViewReportsBothUnreachable() {
        when(adminRepository.existsAny()).thenReturn(true);
        PaymentGateway gateway = reachablePayment();
        TicketIssuer issuer = reachableIssuer();
        SystemAdminService svc = serviceWith(List.of(gateway), List.of(issuer));
        svc.initializePlatform();
        svc.openMarket(openRequest());
        when(gateway.verifyConnection()).thenReturn(false);
        when(issuer.verifyConnection()).thenThrow(new ExternalServiceUnavailableException("WSEP hangs"));

        svc.refreshServiceReachability();

        MarketStateDTO state = svc.viewMarketState(ADMIN_TOKEN);
        assertFalse(state.paymentGatewayHealthy(), "the refresh's payment probe is reported while OPEN");
        assertFalse(state.ticketIssuerHealthy(), "the refresh's issuer probe is reported while OPEN");
        assertEquals("OPEN", state.currentStatus(), "a refresh probes; it never changes the market state");
    }

    @Test
    void GivenNoProbeHasRunYet_WhenViewMarketState_ThenNoProbeTimeIsReported() {
        SystemAdminService svc = serviceWith(List.of(reachablePayment()), List.of(reachableIssuer()));

        assertNull(svc.viewMarketState(ADMIN_TOKEN).lastProbedAt(), "no probe has run, so nothing was checked");
    }

    // The self-heal scheduler refreshes only when this tick's ensureMarketOpen did not probe already.
    @Test
    void GivenUninitializedPlatform_WhenEnsureMarketOpen_ThenItReportsThatItProbed() {
        when(adminRepository.existsAny()).thenReturn(true);
        SystemAdminService svc = serviceWith(List.of(reachablePayment()), List.of(reachableIssuer()));

        assertTrue(svc.ensureMarketOpen(), "init and open probed WSEP in this call");
    }

    @Test
    void GivenOpenMarket_WhenEnsureMarketOpen_ThenItReportsNoProbe() {
        SystemAdminService svc = readyService();
        svc.openMarket(openRequest());

        assertFalse(svc.ensureMarketOpen(), "an open market is left alone, so nothing was probed");
    }
}
