package com.ticketing.system.governance.application.service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ticketing.system.governance.config.AnalyticsProperties;
import com.ticketing.system.identity.application.port.out.AdminRepository;
import com.ticketing.system.identity.application.port.out.SessionManager;
import com.ticketing.system.shared.dto.AdminOverviewDTO;
import com.ticketing.system.shared.dto.GlobalHistoryFiltersDTO;
import com.ticketing.system.shared.dto.SystemAnalyticsDTO;
import com.ticketing.system.shared.exception.InvalidTokenException;
import com.ticketing.system.shared.exception.SessionExpiredException;
import com.ticketing.system.shared.exception.UnauthorizedActionException;
import com.ticketing.system.shared.metrics.ISystemMetrics;
import com.ticketing.system.shared.metrics.MetricType;
import com.ticketing.system.organization.application.port.out.ProductionCompanyRepository;
import com.ticketing.system.catalog.domain.EventStatus;
import com.ticketing.system.catalog.application.port.out.EventRepository;
import com.ticketing.system.messaging.domain.ConversationType;
import com.ticketing.system.messaging.application.port.out.ConversationRepository;
import com.ticketing.system.sales.application.port.out.OrderReceiptRepository;
import com.ticketing.system.sales.domain.OrderReceipt;

import lombok.extern.slf4j.Slf4j;

/**
 * Read-side aggregator for the System Analytics dashboard (UC-46 / #43, #279).
 *
 * <p>Pure query service. Visitor / registration / reservation activity comes
 * from the live {@link ISystemMetrics} counters; purchases are derived from the
 * immutable {@link OrderReceipt} history (real timestamps), so no checkout
 * instrumentation is needed. Market status itself is supplied separately by
 * {@code SystemAdminService}.
 *
 * <p>Both reads are admin-only and take the caller's token: this service is the boundary that
 * enforces it, not the admin route's capability check. The gate is
 * {@code SystemAdminService.requireSystemAdmin}'s: a live admin token (ADR-012) naming an admin who
 * still exists.
 */
@Service
@Slf4j
@Transactional(readOnly = true)
public class SystemAnalyticsService {

    private static final int THROUGHPUT_WINDOW_MINUTES = 60;
    private static final int REVENUE_WINDOW_DAYS = 30;

    private final ISystemMetrics metrics;
    private final OrderReceiptRepository orderReceiptRepository;
    private final ProductionCompanyRepository companyRepository;
    private final EventRepository eventRepository;
    private final ConversationRepository conversationRepository;
    private final SessionManager sessionManager;
    private final AdminRepository adminRepository;
    private final Clock clock;
    private final int windowMinutes;

    public SystemAnalyticsService(
            ISystemMetrics metrics,
            OrderReceiptRepository orderReceiptRepository,
            ProductionCompanyRepository companyRepository,
            EventRepository eventRepository,
            ConversationRepository conversationRepository,
            SessionManager sessionManager,
            AdminRepository adminRepository,
            Clock clock,
            AnalyticsProperties analyticsProperties) {
        this.metrics = metrics;
        this.orderReceiptRepository = orderReceiptRepository;
        this.companyRepository = companyRepository;
        this.eventRepository = eventRepository;
        this.conversationRepository = conversationRepository;
        this.sessionManager = sessionManager;
        this.adminRepository = adminRepository;
        this.clock = clock;
        this.windowMinutes = analyticsProperties.rateWindowMinutes();
    }

    /**
     * Builds the live analytics snapshot: rates over the trailing window, throughput over the hour.
     *
     * @param adminToken the caller's admin token
     * @throws UnauthorizedActionException if the token is missing, or names an admin who no longer exists
     * @throws InvalidTokenException       if the token is malformed, revoked or not an admin token
     * @throws SessionExpiredException     if the token's session has expired
     */
    public SystemAnalyticsDTO computeAnalytics(String adminToken) {
        requireSystemAdmin(adminToken);
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
     * 30 days.
     *
     * @param adminToken the caller's admin token
     * @throws UnauthorizedActionException if the token is missing, or names an admin who no longer exists
     * @throws InvalidTokenException       if the token is malformed, revoked or not an admin token
     * @throws SessionExpiredException     if the token's session has expired
     */
    public AdminOverviewDTO adminOverview(String adminToken) {
        requireSystemAdmin(adminToken);
        int activeCompanies = companyRepository.findActive().size();
        int liveEvents = eventRepository.findByStatus(EventStatus.ON_SALE).size();
        int openComplaints = (int) conversationRepository.findByType(ConversationType.COMPLAINT).stream()
                .filter(conversation -> !conversation.isClosed())
                .count();

        LocalDateTime cutoff = LocalDateTime.now(clock).minusDays(REVENUE_WINDOW_DAYS);
        double revenue30d = 0.0;
        for (OrderReceipt receipt : allReceipts()) {
            LocalDateTime purchasedAt = receipt.getPurchaseTime();
            if (purchasedAt != null && !purchasedAt.isBefore(cutoff)) {
                revenue30d += receipt.netAmount(); // a partly refunded order still earns its other lines (#21)
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

    private void requireSystemAdmin(String adminToken) {
        // false only for a missing token; every invalid token throws, and its id cannot be trusted.
        if (!sessionManager.validateAdminToken(adminToken)) {
            log.warn("Unauthorized analytics access attempt without a token");
            throw new UnauthorizedActionException("Invalid or non-admin token.");
        }
        int adminId = sessionManager.extractAdminId(adminToken);
        if (adminRepository.findById(adminId) == null) {
            log.warn("Unauthorized analytics access attempt with id: {}", adminId);
            throw new UnauthorizedActionException("Invalid or non-admin token.");
        }
    }

    private double perMinute(long countInWindow) {
        return windowMinutes <= 0 ? 0.0 : (double) countInWindow / windowMinutes;
    }
}
