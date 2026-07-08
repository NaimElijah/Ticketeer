package com.ticketing.system.unit.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.Core.Application.dto.AdminOverviewDTO;
import com.ticketing.system.Core.Application.dto.SystemAnalyticsDTO;
import com.ticketing.system.shared.metrics.ISystemMetrics;
import com.ticketing.system.shared.metrics.MetricType;
import com.ticketing.system.governance.application.service.SystemAnalyticsService;
import com.ticketing.system.organization.application.port.out.ProductionCompanyRepository;
import com.ticketing.system.organization.domain.ProductionCompany;
import com.ticketing.system.catalog.domain.Event;
import com.ticketing.system.catalog.domain.EventStatus;
import com.ticketing.system.catalog.application.port.out.EventRepository;
import com.ticketing.system.messaging.domain.Conversation;
import com.ticketing.system.messaging.domain.ConversationType;
import com.ticketing.system.messaging.application.port.out.ConversationRepository;
import com.ticketing.system.sales.application.port.out.OrderReceiptRepository;
import com.ticketing.system.sales.domain.OrderReceipt;

/**
 * Unit tests for {@link SystemAnalyticsService} (UC-46 / #43, #279). The metrics
 * port and order-receipt repository are stubbed so the rate / throughput math
 * and the purchase derivation are verified in isolation.
 */
class SystemAnalyticsServiceTest {

    private static final Instant T0 = Instant.parse("2026-01-01T12:00:00Z");
    private static final int WINDOW = 5;

    private ISystemMetrics metrics;
    private OrderReceiptRepository orderReceiptRepository;
    private ProductionCompanyRepository companyRepository;
    private EventRepository eventRepository;
    private ConversationRepository conversationRepository;
    private SystemAnalyticsService service;

    @BeforeEach
    void setUp() {
        metrics = mock(ISystemMetrics.class);
        orderReceiptRepository = mock(OrderReceiptRepository.class);
        companyRepository = mock(ProductionCompanyRepository.class);
        eventRepository = mock(EventRepository.class);
        conversationRepository = mock(ConversationRepository.class);
        Clock clock = Clock.fixed(T0, ZoneOffset.UTC);
        service = new SystemAnalyticsService(metrics, orderReceiptRepository, companyRepository,
                eventRepository, conversationRepository, clock, WINDOW);
    }

    @Test
    void computeAnalytics_computesRatesThroughputAndDerivesPurchasesFromReceipts() {
        when(metrics.count(MetricType.VISITOR_ENTRY, Duration.ofMinutes(WINDOW))).thenReturn(10L);
        when(metrics.count(MetricType.VISITOR_EXIT, Duration.ofMinutes(WINDOW))).thenReturn(5L);
        when(metrics.count(MetricType.REGISTRATION, Duration.ofMinutes(WINDOW))).thenReturn(0L);
        when(metrics.count(MetricType.RESERVATION, Duration.ofMinutes(WINDOW))).thenReturn(15L);
        when(metrics.count(MetricType.RESERVATION, Duration.ofMinutes(60))).thenReturn(40L);

        when(metrics.total(MetricType.VISITOR_ENTRY)).thenReturn(100L);
        when(metrics.total(MetricType.VISITOR_EXIT)).thenReturn(30L);
        when(metrics.total(MetricType.REGISTRATION)).thenReturn(8L);
        when(metrics.total(MetricType.RESERVATION)).thenReturn(50L);

        // Two receipts inside the 5-min window, one only inside the hour. Build the stubbed
        // receipts first — nesting receiptAt()'s own when(...) inside thenReturn(...) would
        // trip Mockito's UnfinishedStubbing check.
        LocalDateTime base = LocalDateTime.ofInstant(T0, ZoneOffset.UTC);
        OrderReceipt now = receiptAt(base);
        OrderReceipt twoMinAgo = receiptAt(base.minusMinutes(2));
        OrderReceipt thirtyMinAgo = receiptAt(base.minusMinutes(30));
        when(orderReceiptRepository.findGlobal(any())).thenReturn(List.of(now, twoMinAgo, thirtyMinAgo));

        SystemAnalyticsDTO dto = service.computeAnalytics();

        assertEquals(70L, dto.activeVisitors());
        assertEquals(2.0, dto.visitorEntryRatePerMin(), 1e-9);   // 10 / 5
        assertEquals(1.0, dto.visitorExitRatePerMin(), 1e-9);    // 5 / 5
        assertEquals(0.0, dto.registrationRatePerMin(), 1e-9);
        assertEquals(3.0, dto.reservationRatePerMin(), 1e-9);    // 15 / 5
        assertEquals(0.4, dto.purchaseRatePerMin(), 1e-9);       // 2 receipts / 5
        assertEquals(40L, dto.reservationThroughputHr());
        assertEquals(3L, dto.purchaseThroughputHr());            // all three within the hour
        assertEquals(100L, dto.totalVisitors());
        assertEquals(8L, dto.totalRegistrations());
        assertEquals(50L, dto.totalReservations());
        assertEquals(WINDOW, dto.windowMinutes());
    }

    @Test
    void computeAnalytics_clampsActiveVisitorsAtZero_whenExitsExceedEntries() {
        when(metrics.total(MetricType.VISITOR_ENTRY)).thenReturn(3L);
        when(metrics.total(MetricType.VISITOR_EXIT)).thenReturn(8L);
        when(orderReceiptRepository.findGlobal(any())).thenReturn(List.of());

        SystemAnalyticsDTO dto = service.computeAnalytics();

        assertEquals(0L, dto.activeVisitors());
        assertEquals(0.0, dto.purchaseRatePerMin(), 1e-9);
        assertEquals(0L, dto.purchaseThroughputHr());
    }

    private static OrderReceipt receiptAt(LocalDateTime purchaseTime) {
        OrderReceipt receipt = mock(OrderReceipt.class);
        when(receipt.getPurchaseTime()).thenReturn(purchaseTime);
        return receipt;
    }

    @Test
    void adminOverview_countsActiveCompaniesLiveEventsOpenComplaints_andSums30dRevenue() {
        when(companyRepository.findActive()).thenReturn(List.of(
                mock(ProductionCompany.class), mock(ProductionCompany.class)));               // 2 active
        when(eventRepository.findByStatus(EventStatus.ON_SALE)).thenReturn(List.of(
                mock(Event.class), mock(Event.class), mock(Event.class)));                    // 3 live

        // Build the stubbed conversations first — nesting conversation()'s own when(...) inside
        // thenReturn(...) would trip Mockito's UnfinishedStubbing check.
        Conversation openA = conversation(false);
        Conversation openB = conversation(false);
        Conversation closed = conversation(true);
        when(conversationRepository.findByType(ConversationType.COMPLAINT))
                .thenReturn(List.of(openA, openB, closed));                                   // 2 open, 1 closed

        LocalDateTime base = LocalDateTime.ofInstant(T0, ZoneOffset.UTC);
        OrderReceipt inWindow = receipt(base, false, 100.0);                  // counts
        OrderReceipt alsoInWindow = receipt(base.minusDays(10), false, 50.0); // counts
        OrderReceipt refunded = receipt(base, true, 999.0);                   // excluded: refunded
        OrderReceipt tooOld = receipt(base.minusDays(40), false, 999.0);      // excluded: > 30d
        when(orderReceiptRepository.findGlobal(any()))
                .thenReturn(List.of(inWindow, alsoInWindow, refunded, tooOld));

        AdminOverviewDTO dto = service.adminOverview();

        assertEquals(2, dto.activeCompanies());
        assertEquals(3, dto.liveEvents());
        assertEquals(2, dto.openComplaints());
        assertEquals(150.0, dto.revenue30d(), 1e-9);
    }

    private static Conversation conversation(boolean closed) {
        Conversation conversation = mock(Conversation.class);
        when(conversation.isClosed()).thenReturn(closed);
        return conversation;
    }

    private static OrderReceipt receipt(LocalDateTime purchaseTime, boolean refunded, double total) {
        OrderReceipt receipt = mock(OrderReceipt.class);
        when(receipt.getPurchaseTime()).thenReturn(purchaseTime);
        when(receipt.wasRefunded()).thenReturn(refunded);
        when(receipt.getTotalAmount()).thenReturn(total);
        return receipt;
    }
}
