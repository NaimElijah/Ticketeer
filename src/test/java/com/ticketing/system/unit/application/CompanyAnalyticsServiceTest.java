package com.ticketing.system.unit.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.shared.dto.CompanyDashboardDTO;
import com.ticketing.system.sales.application.dto.PurchaseHistoryDTO;
import com.ticketing.system.reporting.application.service.CompanyAnalyticsService;
import com.ticketing.system.organization.application.service.CompanyMembershipService;
import com.ticketing.system.sales.application.port.out.TicketRepository;
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
import com.ticketing.system.shared.exception.CompanyNotFoundException;
import com.ticketing.system.shared.exception.InvalidTokenException;
import com.ticketing.system.shared.exception.UnauthorizedActionException;
import com.ticketing.system.shared.exception.UserNotFoundException;

class CompanyAnalyticsServiceTest {

    private static final int COMPANY_ID = 10;
    // The company's two events; 999 belongs to a different company.
    private static final int EVENT_A = 100;
    private static final int EVENT_B = 101;
    private static final int OTHER_COMPANY_EVENT = 999;

    // Caller fixtures for the token-authorized reads (#22).
    private static final String OWNER_TOKEN = "owner-token";
    private static final String TARGET_TOKEN = "target-token";
    private static final String INVALID_TOKEN = "invalid-token";
    private static final int OWNER_ID = 1;
    private static final int TARGET_USER_ID = 2;
    private static final String COMPANY_1_NAME = "Company1";
    private static final String COMPANY_1_DESCRIPTION = "A test production company1";

    private EventRepository eventRepository;
    private CatalogEventDisplayPort eventDisplayPort;
    private OrderReceiptRepository orderReceiptRepository;
    private ConversationRepository conversationRepository;
    private  TicketRepository ticketRepository;
    private  ProductionCompanyRepository companyRepository;
    private  UserRepository userRepository;
    // Mocked membership service — the authorization gate asks it by userId.
    private CompanyMembershipService membershipService;
    // Session/token port used to authenticate the caller.
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

    private static ProductionCompany company() {
        return new ProductionCompany(COMPANY_ID, OWNER_ID, COMPANY_1_NAME,
                CompanyStatus.ACTIVE, COMPANY_1_DESCRIPTION, 4.5);
    }

    // An authenticated, active member of COMPANY_ID holding exactly the given permissions.
    private void givenMember(String token, int userId, Permission... permissions) {
        when(sessionManager.validateToken(token)).thenReturn(true);
        when(sessionManager.extractUserId(token)).thenReturn(userId);
        when(companyRepository.getCompanyById(COMPANY_ID)).thenReturn(company());
        when(userRepository.getUserById(userId)).thenReturn(mock(User.class));
        when(membershipService.isMemberInCompany(userId, COMPANY_ID)).thenReturn(true);
        for (Permission permission : permissions) {
            when(membershipService.hasPermissionInCompany(userId, COMPANY_ID, permission)).thenReturn(true);
        }
    }

    // Owners implicitly hold every permission (CompanyAppointment.hasPermission).
    private void givenOwner() {
        givenMember(OWNER_TOKEN, OWNER_ID, Permission.values());
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
        givenOwner();
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

        CompanyDashboardDTO stats = service.dashboard(OWNER_TOKEN, COMPANY_ID);

        assertEquals(2, stats.activeEvents());
        assertEquals(3, stats.ticketsSold30d());           // 1 (mixed) + 2 (twoTickets)
        assertEquals(90.0, stats.revenue30d());            // 50 + 20 + 20
        assertEquals(1, stats.openInquiries());
    }

    @Test
    void dashboard_freshCompanyWithNoEvents_returnsZeros() {
        givenOwner();
        when(eventRepository.findActiveByCompany(COMPANY_ID)).thenReturn(List.of());
        when(eventRepository.findIdsByCompany(COMPANY_ID)).thenReturn(List.of());
        when(conversationRepository.findByCompanyAsCounterparty(COMPANY_ID)).thenReturn(List.of());

        CompanyDashboardDTO stats = service.dashboard(OWNER_TOKEN, COMPANY_ID);

        assertEquals(0, stats.activeEvents());
        assertEquals(0, stats.ticketsSold30d());
        assertEquals(0.0, stats.revenue30d());
        assertEquals(0, stats.openInquiries());
        assertNull(stats.rating()); // no events → no derived rating
    }

    @Test
    void dashboard_derivesRatingAsMeanOfCompanyEventRatings() {
        givenOwner();
        when(eventRepository.findActiveByCompany(COMPANY_ID)).thenReturn(List.of());
        when(eventRepository.findIdsByCompany(COMPANY_ID)).thenReturn(List.of());
        when(conversationRepository.findByCompanyAsCounterparty(COMPANY_ID)).thenReturn(List.of());
        // Two rated events + one unrated: mean(4.8, 4.9) = 4.85 -> 4.9 (unrated ignored).
        Event r1 = ratedEvent(4.8);
        Event r2 = ratedEvent(4.9);
        Event unrated = ratedEvent(null);
        when(eventRepository.findByCompanyId(COMPANY_ID)).thenReturn(List.of(r1, r2, unrated));

        assertEquals(4.9, service.dashboard(OWNER_TOKEN, COMPANY_ID).rating(), 0.0001);
    }


    // ─── dashboard authorization (#22) ────────────────────────────────────────────

    @Test
    void GivenInvalidToken_WhenDashboard_ThenThrowsInvalidTokenException() {
        when(sessionManager.validateToken(INVALID_TOKEN)).thenReturn(false);

        assertThrows(InvalidTokenException.class, () -> service.dashboard(INVALID_TOKEN, COMPANY_ID));
        verify(eventRepository, never()).findActiveByCompany(anyInt());
    }

    @Test
    void GivenUnknownCompany_WhenDashboard_ThenThrowsCompanyNotFoundException() {
        when(sessionManager.validateToken(OWNER_TOKEN)).thenReturn(true);
        when(sessionManager.extractUserId(OWNER_TOKEN)).thenReturn(OWNER_ID);
        when(companyRepository.getCompanyById(COMPANY_ID)).thenThrow(new CompanyNotFoundException(COMPANY_ID));

        assertThrows(CompanyNotFoundException.class, () -> service.dashboard(OWNER_TOKEN, COMPANY_ID));
    }

    @Test
    void GivenDeletedUser_WhenDashboard_ThenThrowsUserNotFoundException() {
        givenOwner();
        when(userRepository.getUserById(OWNER_ID)).thenThrow(new UserNotFoundException(OWNER_ID));

        assertThrows(UserNotFoundException.class, () -> service.dashboard(OWNER_TOKEN, COMPANY_ID));
    }

    @Test
    void GivenNonMember_WhenDashboard_ThenThrowsUnauthorizedActionException() {
        givenMember(TARGET_TOKEN, TARGET_USER_ID);
        when(membershipService.isMemberInCompany(TARGET_USER_ID, COMPANY_ID)).thenReturn(false);

        assertThrows(UnauthorizedActionException.class, () -> service.dashboard(TARGET_TOKEN, COMPANY_ID));
        verify(orderReceiptRepository, never()).findByEventIds(anyList());
    }

    @Test
    void GivenMemberWithoutViewSales_WhenDashboard_ThenRevenueIsWithheld() {
        givenMember(TARGET_TOKEN, TARGET_USER_ID, Permission.RESPOND_TO_INQUIRIES);
        when(eventRepository.findActiveByCompany(COMPANY_ID)).thenReturn(List.of(mock(Event.class)));
        when(eventRepository.findIdsByCompany(COMPANY_ID)).thenReturn(List.of(EVENT_A));
        OrderReceipt sale = receipt(false, LocalDateTime.now().minusDays(1), List.of(line(1, 50.0, EVENT_A)));
        when(orderReceiptRepository.findByEventIds(List.of(EVENT_A))).thenReturn(List.of(sale));
        when(conversationRepository.findByCompanyAsCounterparty(COMPANY_ID)).thenReturn(List.of());

        CompanyDashboardDTO stats = service.dashboard(TARGET_TOKEN, COMPANY_ID);

        assertNull(stats.revenue30d());
        assertEquals(1, stats.activeEvents());
        assertEquals(1, stats.ticketsSold30d());
        assertEquals(0, stats.openInquiries());
    }

    @Test
    void GivenMemberWithoutRespondToInquiries_WhenDashboard_ThenOpenInquiriesAreWithheld() {
        givenMember(TARGET_TOKEN, TARGET_USER_ID, Permission.VIEW_SALES);
        when(eventRepository.findActiveByCompany(COMPANY_ID)).thenReturn(List.of());
        when(eventRepository.findIdsByCompany(COMPANY_ID)).thenReturn(List.of());

        CompanyDashboardDTO stats = service.dashboard(TARGET_TOKEN, COMPANY_ID);

        assertNull(stats.openInquiries());
        assertEquals(0.0, stats.revenue30d());
        verify(conversationRepository, never()).findByCompanyAsCounterparty(anyInt());
    }

    // ─── salesHistory authorization (#22, UC-22) ──────────────────────────────────

    @Test
    void GivenInvalidToken_WhenSalesHistory_ThenThrowsInvalidTokenException() {
        when(sessionManager.validateToken(INVALID_TOKEN)).thenReturn(false);

        assertThrows(InvalidTokenException.class, () -> service.salesHistory(INVALID_TOKEN, COMPANY_ID));
    }

    @Test
    void GivenUnknownCompany_WhenSalesHistory_ThenThrowsCompanyNotFoundException() {
        when(sessionManager.validateToken(OWNER_TOKEN)).thenReturn(true);
        when(sessionManager.extractUserId(OWNER_TOKEN)).thenReturn(OWNER_ID);
        when(companyRepository.getCompanyById(COMPANY_ID)).thenThrow(new CompanyNotFoundException(COMPANY_ID));

        assertThrows(CompanyNotFoundException.class, () -> service.salesHistory(OWNER_TOKEN, COMPANY_ID));
    }

    @Test
    void GivenDeletedUser_WhenSalesHistory_ThenThrowsUserNotFoundException() {
        givenOwner();
        when(userRepository.getUserById(OWNER_ID)).thenThrow(new UserNotFoundException(OWNER_ID));

        assertThrows(UserNotFoundException.class, () -> service.salesHistory(OWNER_TOKEN, COMPANY_ID));
    }

    @Test
    void GivenNonMember_WhenSalesHistory_ThenThrowsUnauthorizedActionException() {
        givenMember(TARGET_TOKEN, TARGET_USER_ID);
        when(membershipService.isMemberInCompany(TARGET_USER_ID, COMPANY_ID)).thenReturn(false);

        assertThrows(UnauthorizedActionException.class, () -> service.salesHistory(TARGET_TOKEN, COMPANY_ID));
    }

    @Test
    void GivenMemberWithoutViewSales_WhenSalesHistory_ThenThrowsUnauthorizedActionException() {
        givenMember(TARGET_TOKEN, TARGET_USER_ID, Permission.MANAGE_INVENTORY);

        assertThrows(UnauthorizedActionException.class, () -> service.salesHistory(TARGET_TOKEN, COMPANY_ID));
        verify(orderReceiptRepository, never()).findByEventIds(anyList());
    }

    @Test
    void GivenManagerWithViewSales_WhenSalesHistory_ThenReturnsCompanySales() {
        givenMember(TARGET_TOKEN, TARGET_USER_ID, Permission.VIEW_SALES);
        when(eventRepository.findIdsByCompany(COMPANY_ID)).thenReturn(List.of(EVENT_A));
        OrderReceipt r = fullReceipt(false, LocalDateTime.now().minusDays(1), List.of(line(1, 40.0, EVENT_A)), 1);
        when(orderReceiptRepository.findByEventIds(List.of(EVENT_A))).thenReturn(List.of(r));
        when(ticketRepository.findByOrderReceiptId(1)).thenReturn(List.of());

        PurchaseHistoryDTO result = service.salesHistory(TARGET_TOKEN, COMPANY_ID);

        assertEquals(1, result.records().size());
    }

    // ─── salesHistory unit tests ──────────────────────────────────────────────────

    @Test
    void salesHistory_noEventsForCompany_returnsEmptyHistory() {
        givenOwner();
        when(eventRepository.findIdsByCompany(COMPANY_ID)).thenReturn(List.of());

        PurchaseHistoryDTO result = service.salesHistory(OWNER_TOKEN, COMPANY_ID);

        assertTrue(result.records().isEmpty());
    }

    @Test
    void salesHistory_eventsExistButNoReceipts_returnsEmptyHistory() {
        givenOwner();
        when(eventRepository.findIdsByCompany(COMPANY_ID)).thenReturn(List.of(EVENT_A, EVENT_B));
        when(orderReceiptRepository.findByEventIds(List.of(EVENT_A, EVENT_B))).thenReturn(List.of());

        PurchaseHistoryDTO result = service.salesHistory(OWNER_TOKEN, COMPANY_ID);

        assertTrue(result.records().isEmpty());
    }

    @Test
    void salesHistory_twoReceipts_returnsTwoRecords() {
        givenOwner();
        when(eventRepository.findIdsByCompany(COMPANY_ID)).thenReturn(List.of(EVENT_A));
        OrderReceipt r1 = fullReceipt(false, LocalDateTime.now().minusDays(1),
                List.of(line(1, 50.0, EVENT_A)), 1);
        OrderReceipt r2 = fullReceipt(false, LocalDateTime.now().minusDays(2),
                List.of(line(2, 30.0, EVENT_A)), 2);
        when(orderReceiptRepository.findByEventIds(List.of(EVENT_A))).thenReturn(List.of(r1, r2));
        when(ticketRepository.findByOrderReceiptId(anyInt())).thenReturn(List.of());

        PurchaseHistoryDTO result = service.salesHistory(OWNER_TOKEN, COMPANY_ID);

        assertEquals(2, result.records().size());
    }

    @Test
    void salesHistory_refundedReceiptIsIncluded() {
        givenOwner();
        when(eventRepository.findIdsByCompany(COMPANY_ID)).thenReturn(List.of(EVENT_A));
        OrderReceipt r = fullReceipt(true, LocalDateTime.now().minusDays(3),
                List.of(line(1, 40.0, EVENT_A)), 1);
        when(orderReceiptRepository.findByEventIds(List.of(EVENT_A))).thenReturn(List.of(r));
        when(ticketRepository.findByOrderReceiptId(1)).thenReturn(List.of());

        PurchaseHistoryDTO result = service.salesHistory(OWNER_TOKEN, COMPANY_ID);

        assertEquals(1, result.records().size());
        assertTrue(result.records().get(0).refunded());
    }

    @Test
    void salesHistory_linesFromOtherCompanyEventsAreExcluded() {
        givenOwner();
        when(eventRepository.findIdsByCompany(COMPANY_ID)).thenReturn(List.of(EVENT_A));
        // receipt contains one line for EVENT_A and one for a different company's event
        OrderReceipt r = fullReceipt(false, LocalDateTime.now().minusDays(1),
                List.of(line(1, 100.0, EVENT_A), line(2, 200.0, OTHER_COMPANY_EVENT)), 1);
        when(orderReceiptRepository.findByEventIds(List.of(EVENT_A))).thenReturn(List.of(r));
        when(ticketRepository.findByOrderReceiptId(1)).thenReturn(List.of());

        PurchaseHistoryDTO result = service.salesHistory(OWNER_TOKEN, COMPANY_ID);

        assertEquals(1, result.records().size());
        assertEquals(1,     result.records().get(0).tickets().size()); // only EVENT_A ticket
        assertEquals(100.0, result.records().get(0).totalPaid());      // only EVENT_A revenue
    }
}
