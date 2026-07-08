package com.ticketing.system.unit.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.shared.dto.CompanyDashboardDTO;
import com.ticketing.system.sales.application.dto.PurchaseHistoryDTO;
import com.ticketing.system.organization.application.service.CompanyAnalyticsService;
import com.ticketing.system.sales.application.port.out.TicketRepository;
import com.ticketing.system.organization.application.port.out.ProductionCompanyRepository;
import com.ticketing.system.catalog.domain.Event;
import com.ticketing.system.catalog.application.port.out.EventRepository;
import com.ticketing.system.messaging.domain.Conversation;
import com.ticketing.system.messaging.domain.ConversationType;
import com.ticketing.system.messaging.application.port.out.ConversationRepository;
import com.ticketing.system.sales.application.port.out.OrderReceiptRepository;
import com.ticketing.system.sales.domain.OrderReceipt;
import com.ticketing.system.sales.domain.ReceiptLine;
import com.ticketing.system.identity.application.port.out.UserRepository;

class CompanyAnalyticsServiceTest {

    private static final int COMPANY_ID = 10;
    // The company's two events; 999 belongs to a different company.
    private static final int EVENT_A = 100;
    private static final int EVENT_B = 101;
    private static final int OTHER_COMPANY_EVENT = 999;

    private EventRepository eventRepository;
    private OrderReceiptRepository orderReceiptRepository;
    private ConversationRepository conversationRepository;
    private  TicketRepository ticketRepository;
    private  ProductionCompanyRepository companyRepository;
    private  UserRepository userRepository;
    private CompanyAnalyticsService service;

    @BeforeEach
    void setUp() {
        eventRepository = mock(EventRepository.class);
        orderReceiptRepository = mock(OrderReceiptRepository.class);
        conversationRepository = mock(ConversationRepository.class);
        ticketRepository = mock(TicketRepository.class);
        companyRepository = mock(ProductionCompanyRepository.class);
        userRepository = mock(UserRepository.class);
        service = new CompanyAnalyticsService(eventRepository, orderReceiptRepository, conversationRepository, ticketRepository, companyRepository, userRepository);
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
}
