package com.ticketing.system.Core.Application.services;
import com.ticketing.system.identity.domain.Admin;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ticketing.system.Core.Application.dto.AdminOverviewDTO;
import com.ticketing.system.Core.Application.dto.GlobalHistoryFiltersDTO;
import com.ticketing.system.Core.Application.dto.SystemAnalyticsDTO;
import com.ticketing.system.Core.Application.interfaces.ISystemMetrics;
import com.ticketing.system.Core.Application.interfaces.MetricType;
import com.ticketing.system.organization.application.port.out.ProductionCompanyRepository;
import com.ticketing.system.catalog.domain.EventStatus;
import com.ticketing.system.catalog.application.port.out.EventRepository;
import com.ticketing.system.Core.Domain.messaging.ConversationType;
import com.ticketing.system.Core.Domain.messaging.IConversationRepository;
import com.ticketing.system.Core.Domain.orders.IOrderReceiptRepository;
import com.ticketing.system.Core.Domain.orders.OrderReceipt;

import lombok.extern.slf4j.Slf4j;

/**
 * Read-side aggregator for the System Analytics dashboard (UC-46 / #43, #279).
 *
 * <p>Pure query service. Visitor / registration / reservation activity comes
 * from the live {@link ISystemMetrics} counters; purchases are derived from the
 * immutable {@link OrderReceipt} history (real timestamps), so no checkout
 * instrumentation is needed. No token is taken — the admin route is
 * access-gated, mirroring {@code SystemAdminService.viewMarketState()}. Market
 * status itself is supplied separately by {@code SystemAdminService}.
 */
@Service
@Slf4j
@Transactional(readOnly = true)
public class SystemAnalyticsService {

    private static final int THROUGHPUT_WINDOW_MINUTES = 60;
    private static final int REVENUE_WINDOW_DAYS = 30;

    private final ISystemMetrics metrics;
    private final IOrderReceiptRepository orderReceiptRepository;
    private final ProductionCompanyRepository companyRepository;
    private final EventRepository eventRepository;
    private final IConversationRepository conversationRepository;
    private final Clock clock;
    private final int windowMinutes;

    public SystemAnalyticsService(
            ISystemMetrics metrics,
            IOrderReceiptRepository orderReceiptRepository,
            ProductionCompanyRepository companyRepository,
            EventRepository eventRepository,
            IConversationRepository conversationRepository,
            Clock clock,
            @Value("${analytics.rate-window-minutes:5}") int windowMinutes) {
        this.metrics = metrics;
        this.orderReceiptRepository = orderReceiptRepository;
        this.companyRepository = companyRepository;
        this.eventRepository = eventRepository;
        this.conversationRepository = conversationRepository;
        this.clock = clock;
        this.windowMinutes = windowMinutes > 0 ? windowMinutes : 5;
    }

    /** Builds the live analytics snapshot: rates over the trailing window, throughput over the hour. */
    public SystemAnalyticsDTO computeAnalytics() {
        Duration window = Duration.ofMinutes(windowMinutes);
        Duration hour = Duration.ofMinutes(THROUGHPUT_WINDOW_MINUTES);

        long entries = metrics.count(MetricType.VISITOR_ENTRY, window);
        long exits = metrics.count(MetricType.VISITOR_EXIT, window);
        long registrations = metrics.count(MetricType.REGISTRATION, window);
        long reservations = metrics.count(MetricType.RESERVATION, window);

        long activeVisitors = Math.max(0L,
                metrics.total(MetricType.VISITOR_ENTRY) - metrics.total(MetricType.VISITOR_EXIT));

        // Single receipt scan per snapshot — count both the rate window and the hour throughput
        // in one pass (this runs on the auto-refresh poll every few seconds).
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime windowCutoff = now.minusMinutes(windowMinutes);
        LocalDateTime hourCutoff = now.minusMinutes(THROUGHPUT_WINDOW_MINUTES);
        long purchasesWindow = 0;
        long purchasesHour = 0;
        for (OrderReceipt receipt : allReceipts()) {
            LocalDateTime purchasedAt = receipt.getPurchaseTime();
            if (purchasedAt == null) {
                continue;
            }
            if (!purchasedAt.isBefore(windowCutoff)) {
                purchasesWindow++;
            }
            if (!purchasedAt.isBefore(hourCutoff)) {
                purchasesHour++;
            }
        }

        SystemAnalyticsDTO dto = new SystemAnalyticsDTO(
                activeVisitors,
                perMinute(entries),
                perMinute(exits),
                perMinute(registrations),
                perMinute(reservations),
                perMinute(purchasesWindow),
                metrics.count(MetricType.RESERVATION, hour),
                purchasesHour,
                metrics.total(MetricType.VISITOR_ENTRY),
                metrics.total(MetricType.REGISTRATION),
                metrics.total(MetricType.RESERVATION),
                windowMinutes);
        log.debug("System analytics snapshot: {}", dto);
        return dto;
    }

    /**
     * Headline counters for the admin workspace landing page (#279): active companies, live
     * (ON_SALE) events, open (non-closed) complaints, and non-refunded revenue over the trailing
     * 30 days. No token — the admin route is access-gated, like {@link #computeAnalytics()}.
     */
    public AdminOverviewDTO adminOverview() {
        int activeCompanies = companyRepository.findActive().size();
        int liveEvents = eventRepository.findByStatus(EventStatus.ON_SALE).size();
        int openComplaints = (int) conversationRepository.findByType(ConversationType.COMPLAINT).stream()
                .filter(conversation -> !conversation.isClosed())
                .count();

        LocalDateTime cutoff = LocalDateTime.now(clock).minusDays(REVENUE_WINDOW_DAYS);
        double revenue30d = 0.0;
        for (OrderReceipt receipt : allReceipts()) {
            LocalDateTime purchasedAt = receipt.getPurchaseTime();
            if (!receipt.wasRefunded() && purchasedAt != null && !purchasedAt.isBefore(cutoff)) {
                revenue30d += receipt.getTotalAmount();
            }
        }

        AdminOverviewDTO dto = new AdminOverviewDTO(activeCompanies, liveEvents, openComplaints, revenue30d);
        log.debug("Admin overview snapshot: {}", dto);
        return dto;
    }

    /**
     * All receipts (a fully-null filter matches every receipt). The repo's date filters are
     * {@code LocalDate} — too coarse for minute-level windows — so callers filter by the receipt's
     * own {@code purchaseTime} in memory. Fetched once per snapshot.
     */
    private List<OrderReceipt> allReceipts() {
        return orderReceiptRepository.findGlobal(
                new GlobalHistoryFiltersDTO(null, null, null, null, null));
    }

    private double perMinute(long countInWindow) {
        return windowMinutes <= 0 ? 0.0 : (double) countInWindow / windowMinutes;
    }
}
