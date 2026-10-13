package com.ticketing.system.unit.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.shared.dto.CompanyDashboardDTO;
import com.ticketing.system.sales.application.dto.PurchaseHistoryDTO;
import com.ticketing.system.reporting.application.service.CompanyAnalyticsService;
import com.ticketing.system.organization.application.service.CompanyMembershipService;
import com.ticketing.system.sales.application.port.out.TicketRepository;
import com.ticketing.system.sales.domain.Ticket;
import com.ticketing.system.organization.application.port.out.ProductionCompanyRepository;
import com.ticketing.system.organization.domain.ProductionCompany;
import com.ticketing.system.organization.domain.CompanyStatus;
import com.ticketing.system.organization.domain.Permission;
import com.ticketing.system.catalog.domain.Event;
import com.ticketing.system.catalog.application.port.out.EventRepository;
import com.ticketing.system.catalog.application.port.in.CatalogEventDisplayPort;
import com.ticketing.system.messaging.domain.Conversation;
import com.ticketing.system.messaging.domain.ConversationType;
import com.ticketing.system.messaging.application.port.out.ConversationRepository;
import com.ticketing.system.sales.application.port.out.OrderReceiptRepository;
import com.ticketing.system.sales.domain.OrderReceipt;
import com.ticketing.system.sales.domain.ReceiptLine;
import com.ticketing.system.identity.application.port.out.UserRepository;
import com.ticketing.system.identity.application.port.out.SessionManager;
import com.ticketing.system.identity.domain.User;

class CompanyAnalyticsServiceTest {

    private static final int COMPANY_ID = 10;
    // The company's two events; 999 belongs to a different company.
    private static final int EVENT_A = 100;
    private static final int EVENT_B = 101;
    private static final int OTHER_COMPANY_EVENT = 999;

    // Fixtures for the relocated viewSalesHistory tests (UC-22, moved here from CompanyManagementServiceTest).
    private static final String OWNER_TOKEN = "owner-token";
    private static final String TARGET_TOKEN = "target-token";
    private static final String INVALID_TOKEN = "invalid-token";
    private static final int OWNER_ID = 1;
    private static final int TARGET_USER_ID = 2;
    private static final int ORDER_RECEIPT_ID = 11;
    private static final String COMPANY_1_NAME = "Company1";
    private static final String COMPANY_1_DESCRIPTION = "A test production company1";

    private EventRepository eventRepository;
    private CatalogEventDisplayPort eventDisplayPort;
    private OrderReceiptRepository orderReceiptRepository;
    private ConversationRepository conversationRepository;
    private  TicketRepository ticketRepository;
    private  ProductionCompanyRepository companyRepository;
    private  UserRepository userRepository;
    // Mocked membership service — the viewSalesHistory authorization gate now asks it (by userId)
    // instead of calling User.hasPermissionInCompany (task #20).
    private CompanyMembershipService membershipService;
    // Session/token port used by viewSalesHistory's token authentication.
    private SessionManager sessionManager;
    private CompanyAnalyticsService service;

    @BeforeEach
    void setUp() {
        eventRepository = mock(EventRepository.class);
        eventDisplayPort = mock(CatalogEventDisplayPort.class);
        orderReceiptRepository = mock(OrderReceiptRepository.class);
        conversationRepository = mock(ConversationRepository.class);
        ticketRepository = mock(TicketRepository.class);
        companyRepository = mock(ProductionCompanyRepository.class);
        userRepository = mock(UserRepository.class);
        membershipService = mock(CompanyMembershipService.class);
        sessionManager = mock(SessionManager.class);
        service = new CompanyAnalyticsService(eventRepository, eventDisplayPort, orderReceiptRepository, conversationRepository, ticketRepository, companyRepository, userRepository, membershipService, sessionManager);
    }

    private static ReceiptLine line(int ticketId, double price, int eventId) {
        return new ReceiptLine(ticketId, price, eventId, 1, null, LocalDateTime.now());
    }

    private static OrderReceipt receipt(boolean refunded, LocalDateTime purchasedAt, List<ReceiptLine> lines) {
        OrderReceipt r = mock(OrderReceipt.class);
        when(r.wasRefunded()).thenReturn(refunded);
        when(r.getPurchaseTime()).thenReturn(purchasedAt);
        when(r.getReceiptLines()).thenReturn(lines);
        return r;
    }


    private static OrderReceipt fullReceipt(boolean refunded, LocalDateTime purchasedAt,
                                         List<ReceiptLine> lines, int id) {
        OrderReceipt r = mock(OrderReceipt.class);
        when(r.wasRefunded()).thenReturn(refunded);
        when(r.getPurchaseTime()).thenReturn(purchasedAt);
        when(r.getReceiptLines()).thenReturn(lines);
        when(r.getId()).thenReturn(id);
        when(r.getTransactionRecords()).thenReturn(List.of());
        when(r.getHolderUserId()).thenReturn(null); // guest order; mapper is null-safe
        when(r.getGuestEmail()).thenReturn(null);
        return r;
    }

    private static Conversation conversation(ConversationType type, boolean closed) {
        Conversation c = mock(Conversation.class);
        when(c.getType()).thenReturn(type);
        when(c.isClosed()).thenReturn(closed);
        return c;
    }

    private static Event ratedEvent(Double rating) {
        Event e = mock(Event.class);
        when(e.getRating()).thenReturn(rating);
        return e;
    }

    @Test
    void dashboard_aggregatesLiveCountersForTheCompany() {
        LocalDateTime now = LocalDateTime.now();

        when(eventRepository.findActiveByCompany(COMPANY_ID))
            .thenReturn(List.of(mock(Event.class), mock(Event.class))); // 2 ON_SALE
        when(eventRepository.findIdsByCompany(COMPANY_ID))
            .thenReturn(List.of(EVENT_A, EVENT_B));

        OrderReceipt mixed = receipt(false, now.minusDays(1),
            List.of(line(1, 50.0, EVENT_A), line(2, 30.0, OTHER_COMPANY_EVENT))); // only EVENT_A counts
        OrderReceipt twoTickets = receipt(false, now.minusDays(5),
            List.of(line(3, 20.0, EVENT_B), line(4, 20.0, EVENT_B)));
        OrderReceipt refunded = receipt(true, now.minusDays(2),
            List.of(line(5, 99.0, EVENT_A)));
        OrderReceipt tooOld = receipt(false, now.minusDays(45),
            List.of(line(6, 77.0, EVENT_A)));
        when(orderReceiptRepository.findByEventIds(List.of(EVENT_A, EVENT_B)))
            .thenReturn(List.of(mixed, twoTickets, refunded, tooOld));

        Conversation openInquiry = conversation(ConversationType.INQUIRY, false);    // counts
        Conversation closedInquiry = conversation(ConversationType.INQUIRY, true);   // closed → excluded
        Conversation complaint = conversation(ConversationType.COMPLAINT, false);    // wrong type → excluded
        when(conversationRepository.findByCompanyAsCounterparty(COMPANY_ID))
            .thenReturn(List.of(openInquiry, closedInquiry, complaint));

        CompanyDashboardDTO stats = service.dashboard(COMPANY_ID);

        assertEquals(2, stats.activeEvents());
        assertEquals(3, stats.ticketsSold30d());           // 1 (mixed) + 2 (twoTickets)
        assertEquals(90.0, stats.revenue30d());            // 50 + 20 + 20
        assertEquals(1, stats.openInquiries());
    }

    @Test
    void dashboard_freshCompanyWithNoEvents_returnsZeros() {
        when(eventRepository.findActiveByCompany(COMPANY_ID)).thenReturn(List.of());
        when(eventRepository.findIdsByCompany(COMPANY_ID)).thenReturn(List.of());
        when(conversationRepository.findByCompanyAsCounterparty(COMPANY_ID)).thenReturn(List.of());

        CompanyDashboardDTO stats = service.dashboard(COMPANY_ID);

        assertEquals(0, stats.activeEvents());
        assertEquals(0, stats.ticketsSold30d());
        assertEquals(0.0, stats.revenue30d());
        assertEquals(0, stats.openInquiries());
        assertNull(stats.rating()); // no events → no derived rating
    }

    @Test
    void dashboard_derivesRatingAsMeanOfCompanyEventRatings() {
        when(eventRepository.findActiveByCompany(COMPANY_ID)).thenReturn(List.of());
        when(eventRepository.findIdsByCompany(COMPANY_ID)).thenReturn(List.of());
        when(conversationRepository.findByCompanyAsCounterparty(COMPANY_ID)).thenReturn(List.of());
        // Two rated events + one unrated: mean(4.8, 4.9) = 4.85 -> 4.9 (unrated ignored).
        Event r1 = ratedEvent(4.8);
        Event r2 = ratedEvent(4.9);
        Event unrated = ratedEvent(null);
        when(eventRepository.findByCompanyId(COMPANY_ID)).thenReturn(List.of(r1, r2, unrated));

        assertEquals(4.9, service.dashboard(COMPANY_ID).rating(), 0.0001);
    }


    // ─── salesHistory unit tests ──────────────────────────────────────────────────

    @Test
    void salesHistory_noEventsForCompany_returnsEmptyHistory() {
        when(eventRepository.findIdsByCompany(COMPANY_ID)).thenReturn(List.of());

        PurchaseHistoryDTO result = service.salesHistory(COMPANY_ID);

        assertTrue(result.records().isEmpty());
    }

    @Test
    void salesHistory_eventsExistButNoReceipts_returnsEmptyHistory() {
        when(eventRepository.findIdsByCompany(COMPANY_ID)).thenReturn(List.of(EVENT_A, EVENT_B));
        when(orderReceiptRepository.findByEventIds(List.of(EVENT_A, EVENT_B))).thenReturn(List.of());

        PurchaseHistoryDTO result = service.salesHistory(COMPANY_ID);

        assertTrue(result.records().isEmpty());
    }

    @Test
    void salesHistory_twoReceipts_returnsTwoRecords() {
        when(eventRepository.findIdsByCompany(COMPANY_ID)).thenReturn(List.of(EVENT_A));
        OrderReceipt r1 = fullReceipt(false, LocalDateTime.now().minusDays(1),
                List.of(line(1, 50.0, EVENT_A)), 1);
        OrderReceipt r2 = fullReceipt(false, LocalDateTime.now().minusDays(2),
                List.of(line(2, 30.0, EVENT_A)), 2);
        when(orderReceiptRepository.findByEventIds(List.of(EVENT_A))).thenReturn(List.of(r1, r2));
        when(ticketRepository.findByOrderReceiptId(anyInt())).thenReturn(List.of());

        PurchaseHistoryDTO result = service.salesHistory(COMPANY_ID);

        assertEquals(2, result.records().size());
    }

    @Test
    void salesHistory_refundedReceiptIsIncluded() {
        when(eventRepository.findIdsByCompany(COMPANY_ID)).thenReturn(List.of(EVENT_A));
        OrderReceipt r = fullReceipt(true, LocalDateTime.now().minusDays(3),
                List.of(line(1, 40.0, EVENT_A)), 1);
        when(orderReceiptRepository.findByEventIds(List.of(EVENT_A))).thenReturn(List.of(r));
        when(ticketRepository.findByOrderReceiptId(1)).thenReturn(List.of());

        PurchaseHistoryDTO result = service.salesHistory(COMPANY_ID);

        assertEquals(1, result.records().size());
        assertTrue(result.records().get(0).refunded());
    }

    @Test
    void salesHistory_linesFromOtherCompanyEventsAreExcluded() {
        when(eventRepository.findIdsByCompany(COMPANY_ID)).thenReturn(List.of(EVENT_A));
        // receipt contains one line for EVENT_A and one for a different company's event
        OrderReceipt r = fullReceipt(false, LocalDateTime.now().minusDays(1),
                List.of(line(1, 100.0, EVENT_A), line(2, 200.0, OTHER_COMPANY_EVENT)), 1);
        when(orderReceiptRepository.findByEventIds(List.of(EVENT_A))).thenReturn(List.of(r));
        when(ticketRepository.findByOrderReceiptId(1)).thenReturn(List.of());

        PurchaseHistoryDTO result = service.salesHistory(COMPANY_ID);

        assertEquals(1, result.records().size());
        assertEquals(1,     result.records().get(0).tickets().size()); // only EVENT_A ticket
        assertEquals(100.0, result.records().get(0).totalPaid());      // only EVENT_A revenue
    }

    // ─── viewSalesHistory (UC-22) unit tests, relocated from CompanyManagementServiceTest ─────────

    @Test
    void GivenInvalidToken_WhenViewSalesHistory_ThenThrowException() {
        when(sessionManager.validateToken(INVALID_TOKEN)).thenReturn(false); // token fails validation

        assertThrows(RuntimeException.class, () -> service.viewSalesHistory(INVALID_TOKEN, COMPANY_ID));
    }

    @Test
    void GivenCompanyNotFound_WhenViewSalesHistory_ThenThrowException() {
        when(sessionManager.validateToken(OWNER_TOKEN)).thenReturn(true);
        when(sessionManager.extractUserId(OWNER_TOKEN)).thenReturn(OWNER_ID);
        when(companyRepository.getCompanyById(COMPANY_ID)).thenReturn(null); // unknown company

        assertThrows(RuntimeException.class, () -> service.viewSalesHistory(OWNER_TOKEN, COMPANY_ID));
    }

    @Test
    void GivenUserNotFound_WhenViewSalesHistory_ThenThrowException() {
        ProductionCompany company = new ProductionCompany(COMPANY_ID, OWNER_ID, COMPANY_1_NAME,
                CompanyStatus.ACTIVE, COMPANY_1_DESCRIPTION, 4.5);

        when(sessionManager.validateToken(OWNER_TOKEN)).thenReturn(true);
        when(sessionManager.extractUserId(OWNER_TOKEN)).thenReturn(OWNER_ID);
        when(companyRepository.getCompanyById(COMPANY_ID)).thenReturn(company);
        when(userRepository.getUserById(OWNER_ID)).thenReturn(null); // caller no longer exists

        assertThrows(RuntimeException.class, () -> service.viewSalesHistory(OWNER_TOKEN, COMPANY_ID));
    }

    @Test
    void GivenUserWithNoPermission_WhenViewSalesHistory_ThenThrowException() {
        ProductionCompany company = new ProductionCompany(COMPANY_ID, OWNER_ID, COMPANY_1_NAME,
                CompanyStatus.ACTIVE, COMPANY_1_DESCRIPTION, 4.5);
        User requester = mock(User.class);

        when(sessionManager.validateToken(OWNER_TOKEN)).thenReturn(true);
        when(sessionManager.extractUserId(OWNER_TOKEN)).thenReturn(OWNER_ID);
        when(companyRepository.getCompanyById(COMPANY_ID)).thenReturn(company);
        when(userRepository.getUserById(OWNER_ID)).thenReturn(requester);
        when(membershipService.hasPermissionInCompany(OWNER_ID, COMPANY_ID, Permission.VIEW_SALES)).thenReturn(false); // denied

        assertThrows(RuntimeException.class, () -> service.viewSalesHistory(OWNER_TOKEN, COMPANY_ID));
    }

    @Test
    void GivenOwnerWithNoSales_WhenViewSalesHistory_ThenReturnEmptyList() {
        ProductionCompany company = new ProductionCompany(COMPANY_ID, OWNER_ID, COMPANY_1_NAME,
                CompanyStatus.ACTIVE, COMPANY_1_DESCRIPTION, 4.5);
        User ownerUser = mock(User.class);

        when(sessionManager.validateToken(OWNER_TOKEN)).thenReturn(true);
        when(sessionManager.extractUserId(OWNER_TOKEN)).thenReturn(OWNER_ID);
        when(eventRepository.findIdsByCompany(COMPANY_ID)).thenReturn(new ArrayList<>()); // company has no events
        when(companyRepository.getCompanyById(COMPANY_ID)).thenReturn(company);
        when(userRepository.getUserById(OWNER_ID)).thenReturn(ownerUser);
        when(membershipService.hasPermissionInCompany(OWNER_ID, COMPANY_ID, Permission.VIEW_SALES)).thenReturn(true);

        List<PurchaseHistoryDTO> result = service.viewSalesHistory(OWNER_TOKEN, COMPANY_ID);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void GivenManagerWithViewSalesPermission_WhenViewSalesHistory_ThenReturnSalesHistory() {
        ProductionCompany company = new ProductionCompany(COMPANY_ID, OWNER_ID, COMPANY_1_NAME,
                CompanyStatus.ACTIVE, COMPANY_1_DESCRIPTION, 4.5);
        User managerUser = mock(User.class);
        OrderReceipt mockReceipt = mock(OrderReceipt.class);
        Ticket ticket = new Ticket(1, 1, ORDER_RECEIPT_ID, null, 50.0, 10, "BARCODE-001");
        Event mockEvent = mock(Event.class);

        when(sessionManager.validateToken(TARGET_TOKEN)).thenReturn(true);
        when(sessionManager.extractUserId(TARGET_TOKEN)).thenReturn(TARGET_USER_ID);
        when(companyRepository.getCompanyById(COMPANY_ID)).thenReturn(company);
        when(userRepository.getUserById(TARGET_USER_ID)).thenReturn(managerUser);
        when(membershipService.hasPermissionInCompany(TARGET_USER_ID, COMPANY_ID, Permission.VIEW_SALES)).thenReturn(true);

        when(mockReceipt.getId()).thenReturn(42);
        when(mockReceipt.getPurchaseTime()).thenReturn(LocalDateTime.now());
        ReceiptLine mockLine = mock(ReceiptLine.class);
        when(mockLine.getTicketId()).thenReturn(10);
        when(mockLine.getPriceAtReservation()).thenReturn(50.0);
        when(mockReceipt.getReceiptLines()).thenReturn(List.of(mockLine));
        when(ticketRepository.findByOrderReceiptId(42)).thenReturn(List.of(ticket));
        when(eventRepository.findById(1)).thenReturn(mockEvent);
        when(mockEvent.getName()).thenReturn("Rock Concert");
        when(eventRepository.findIdsByCompany(COMPANY_ID)).thenReturn(List.of(1));
        when(orderReceiptRepository.findByEventIds(List.of(1))).thenReturn(List.of(mockReceipt));

        List<PurchaseHistoryDTO> result = service.viewSalesHistory(TARGET_TOKEN, COMPANY_ID);

        assertNotNull(result);
        assertEquals(1, result.size());
        PurchaseHistoryDTO.PurchaseRecordDTO record = result.get(0).records().get(0);
        assertEquals(42, record.orderReceiptId());
        assertEquals(50.0, record.totalPaid());
        assertEquals(1, record.tickets().size());
    }

    @Test
    void GivenOwnerWithMultipleSales_WhenViewSalesHistory_ThenReturnAllRecords() {
        ProductionCompany company = new ProductionCompany(COMPANY_ID, OWNER_ID, COMPANY_1_NAME,
                CompanyStatus.ACTIVE, COMPANY_1_DESCRIPTION, 4.5);
        User ownerUser = mock(User.class);
        OrderReceipt mockReceipt1 = mock(OrderReceipt.class);
        OrderReceipt mockReceipt2 = mock(OrderReceipt.class);
        Event mockEvent = mock(Event.class);

        when(sessionManager.validateToken(OWNER_TOKEN)).thenReturn(true);
        when(sessionManager.extractUserId(OWNER_TOKEN)).thenReturn(OWNER_ID);
        when(companyRepository.getCompanyById(COMPANY_ID)).thenReturn(company);
        when(userRepository.getUserById(OWNER_ID)).thenReturn(ownerUser);
        when(membershipService.hasPermissionInCompany(OWNER_ID, COMPANY_ID, Permission.VIEW_SALES)).thenReturn(true);

        when(mockEvent.getName()).thenReturn("Summer Festival");

        when(mockReceipt1.getId()).thenReturn(1);
        when(mockReceipt1.getPurchaseTime()).thenReturn(LocalDateTime.now());
        when(ticketRepository.findByOrderReceiptId(1)).thenReturn(new ArrayList<>());
        when(eventRepository.findById(10)).thenReturn(mockEvent);

        when(mockReceipt2.getId()).thenReturn(2);
        when(mockReceipt2.getPurchaseTime()).thenReturn(LocalDateTime.now());
        when(ticketRepository.findByOrderReceiptId(2)).thenReturn(new ArrayList<>());
        when(eventRepository.findIdsByCompany(COMPANY_ID)).thenReturn(List.of(10));
        when(orderReceiptRepository.findByEventIds(List.of(10))).thenReturn(List.of(mockReceipt1, mockReceipt2));

        List<PurchaseHistoryDTO> result = service.viewSalesHistory(OWNER_TOKEN, COMPANY_ID);

        assertNotNull(result);
        assertEquals(2, result.size());
    }
}
